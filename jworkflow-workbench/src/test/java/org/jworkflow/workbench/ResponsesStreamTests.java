package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

/** WB-19 provider contract against a local fake: streaming, fixed model, retries, failures and cancellation. */
class ResponsesStreamTests {
    private static ResponsesChatModel model(FakeResponsesServer server, String key, int attempts) {
        var streaming = new ResponsesChatModel.Streaming(HttpClient.newHttpClient(), URI.create(server.baseUrl() + "/v1/responses"), () -> key,
                new ResponsesChatModel.RetrySettings(attempts, Duration.ofMillis(5), 2, Duration.ofMillis(20)));
        return new ResponsesChatModel(null, null, ResponsesChatModel.MODEL, "", streaming);
    }

    private static AssistantFailure failure(ResponsesChatModel model) {
        var error = assertThrows(RuntimeException.class, () -> model.stream(new Prompt("hello")).collectList().block(Duration.ofSeconds(10)));
        return assertInstanceOf(AssistantFailure.class, error);
    }

    @Test void streamsDeltasThenTheFinalResponseWithTheFixedModel() throws Exception {
        try (var server = new FakeResponsesServer()) {
            server.scripts.add(FakeResponsesServer.sse(FakeResponsesServer.delta("Hel"), FakeResponsesServer.delta("lo"),
                    FakeResponsesServer.completed("Hello", "{\"summary\":\"s\",\"operations\":[]}")));
            List<ChatResponse> responses = model(server, "test-key", 1).stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
            assertEquals(3, responses.size());
            assertEquals("Hel", responses.get(0).getResult().getOutput().getText());
            assertEquals(Boolean.TRUE, responses.get(1).getResult().getOutput().getMetadata().get(ResponsesChatModel.DELTA));
            var last = responses.get(2).getResult().getOutput();
            assertNull(last.getMetadata().get(ResponsesChatModel.DELTA));
            assertEquals("propose_workflow_edit", last.getToolCalls().get(0).name());
            var body = JsonMapper.builder().build().readTree(server.bodies.get(0));
            assertEquals("gpt-6-astra", body.path("model").asText()); assertFalse(body.path("store").asBoolean()); assertTrue(body.path("stream").asBoolean());
            assertEquals("Bearer test-key", server.authorizations.get(0));
        }
    }

    @Test void retriesTransientFailuresBeforeOutputUpToConfiguredAttempts() throws Exception {
        try (var server = new FakeResponsesServer()) {
            server.scripts.add(FakeResponsesServer.status(503, "{}")); server.scripts.add(FakeResponsesServer.status(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"));
            server.scripts.add(FakeResponsesServer.sse(FakeResponsesServer.completed("ok", null)));
            assertEquals(1, model(server, "k", 3).stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10)).size());
            assertEquals(3, server.bodies.size());
        }
        try (var server = new FakeResponsesServer()) {
            for (int i = 0; i < 3; i++) server.scripts.add(FakeResponsesServer.status(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"));
            var failure = failure(model(server, "k", 2));
            assertEquals(AssistantFailure.Kind.RATE_LIMITED, failure.kind()); assertTrue(failure.getMessage().contains("rate_limit_exceeded"));
            assertEquals(2, server.bodies.size(), "spring.ai.retry.max-attempts counts the first try");
        }
    }

    @Test void nonTransientFailuresAreClassifiedWithoutRetryOrSecrets() throws Exception {
        record Case(int status, String body, AssistantFailure.Kind kind) {}
        for (var c : List.of(new Case(401, "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"Incorrect key sk-secret-value\"}}", AssistantFailure.Kind.AUTHENTICATION),
                new Case(404, "{\"error\":{\"code\":\"model_not_found\"}}", AssistantFailure.Kind.MODEL_UNAVAILABLE),
                new Case(400, "{\"error\":{\"code\":\"model_not_found\"}}", AssistantFailure.Kind.MODEL_UNAVAILABLE),
                new Case(400, "not json", AssistantFailure.Kind.PROVIDER))) {
            try (var server = new FakeResponsesServer()) {
                server.scripts.add(FakeResponsesServer.status(c.status(), c.body()));
                var failure = failure(model(server, "sk-test-secret-key", 4));
                assertEquals(c.kind(), failure.kind(), c.toString()); assertEquals(1, server.bodies.size());
                assertFalse(failure.getMessage().contains("sk-"), "Messages never echo keys or provider text");
            }
        }
    }

    @Test void interruptedOrMalformedStreamsFailWithoutRetry() throws Exception {
        try (var server = new FakeResponsesServer()) {
            server.scripts.add(FakeResponsesServer.sse(FakeResponsesServer.delta("partial")));
            assertEquals(AssistantFailure.Kind.INTERRUPTED, failure(model(server, "k", 3)).kind()); assertEquals(1, server.bodies.size());
        }
        try (var server = new FakeResponsesServer()) {
            server.scripts.add(FakeResponsesServer.sse("{not json"));
            assertEquals(AssistantFailure.Kind.MALFORMED, failure(model(server, "k", 3)).kind());
        }
        try (var server = new FakeResponsesServer()) {
            server.scripts.add(FakeResponsesServer.sse("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\"}}}"));
            assertEquals(AssistantFailure.Kind.PROVIDER, failure(model(server, "k", 3)).kind());
        }
    }

    @Test void missingKeyMakesNoRequest() throws Exception {
        try (var server = new FakeResponsesServer()) {
            assertEquals(AssistantFailure.Kind.UNAVAILABLE, failure(model(server, " ", 3)).kind());
            assertTrue(server.bodies.isEmpty());
        }
    }

    @Test void disposingTheStreamClosesTheProviderConnection() throws Exception {
        try (var server = new FakeResponsesServer()) {
            server.scripts.add(server.hold("first"));
            List<String> seen = new CopyOnWriteArrayList<>();
            var subscription = model(server, "k", 1).stream(new Prompt("hi")).subscribe(r -> seen.add(r.getResult().getOutput().getText()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (seen.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(List.of("first"), seen);
            long cancelled = System.nanoTime();
            subscription.dispose();
            assertTrue(server.clientGone.await(5, TimeUnit.SECONDS), "Provider connection stays open after cancellation");
            assertTrue(System.nanoTime() - cancelled < TimeUnit.SECONDS.toNanos(5));
            Thread.sleep(300); assertEquals(List.of("first"), seen, "No output after cancellation");
        }
    }
}
