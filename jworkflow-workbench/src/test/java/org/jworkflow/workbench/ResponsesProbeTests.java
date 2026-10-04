package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import tools.jackson.databind.json.JsonMapper;

/** Bounded protocol experiments with fake provider responses. No live model availability claim. */
class ResponsesProbeTests {
    static final String TEXT = "{\"id\":\"mock-id\",\"model\":\"gpt-6-astra\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}]}";
    static final class Fixture {
        final RestClient.Builder builder = RestClient.builder().baseUrl("http://mock.invalid/v1");
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResponsesChatModel model(String effort) {return new ResponsesChatModel(builder.build(), ResponsesConfiguration.retryTemplate(2, Duration.ofMillis(1)), "gpt-6-astra", effort);}
    }
    @Test void protocolPreservesReasoningToolIdsAndResultsWithoutExecutingTools() {
        var f = new Fixture(); var model = f.model("high");
        var callback = FunctionToolCallback.builder("lookup", (String input) -> "never automatically executed")
            .description("Mock tool").inputType(String.class).build();
        var options = ToolCallingChatOptions.builder().model("gpt-6-astra").maxTokens(100).toolCallbacks(callback).build();
        f.server.expect(requestTo("http://mock.invalid/v1/responses")).andExpect(request -> {
            var json = JsonMapper.builder().build().readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
            assertEquals("gpt-6-astra", json.path("model").asText()); assertFalse(json.path("store").asBoolean());
            assertEquals("function", json.path("tools").get(0).path("type").asText()); assertEquals(100, json.path("max_output_tokens").asInt());
            assertEquals("high", json.path("reasoning").path("effort").asText());
        }).andRespond(withSuccess("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"fake-encrypted\"},{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"lookup\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12}}", MediaType.APPLICATION_JSON));
        var first = model.call(new Prompt(List.of(new SystemMessage("safe system"), new UserMessage("hello")), options));
        assertTrue(first.hasToolCalls()); assertEquals(12, first.getMetadata().getUsage().getTotalTokens());
        f.server.verify(); f.server.reset();
        f.server.expect(requestTo("http://mock.invalid/v1/responses")).andExpect(content().string(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("fake-encrypted"), org.hamcrest.Matchers.containsString("function_call_output"), org.hamcrest.Matchers.containsString("call-1"))))
            .andRespond(withSuccess(TEXT, MediaType.APPLICATION_JSON));
        var toolResult = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "lookup", "mock-result"))).build();
        assertEquals("hello", model.call(new Prompt(List.of(first.getResult().getOutput(), toolResult))).getResult().getOutput().getText());
        f.server.verify(); assertEquals("gpt-6-astra", model.getOptions().getModel());
    }
    @Test void plainAssistantCallsRefusalsAndUnknownContent() {
        var f = new Fixture();
        var assistant = AssistantMessage.builder().content("previous").toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "lookup", "{}"))).build();
        f.server.expect(anything()).andExpect(content().string(org.hamcrest.Matchers.containsString("function_call"))).andRespond(withSuccess("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"ignored\"},{\"type\":\"output_text\",\"text\":\"first\"},{\"type\":\"refusal\",\"refusal\":\"cannot\"}]}]}", MediaType.APPLICATION_JSON));
        assertEquals("first\ncannot", f.model("").call(new Prompt(List.of(assistant, new UserMessage("next")))).getResult().getOutput().getText()); f.server.verify();
    }
    @Test void invalidProviderPayloadsFailExplicitly() {
        for (String response : List.of("", " ", "{\"status\":\"failed\"}", "{\"status\":\"completed\"}", "{\"status\":\"completed\",\"output\":[]}", "{\"status\":\"completed\",\"output\":[{\"type\":\"hosted_tool\"}]}")) {
            var f = new Fixture(); f.server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertThrows(IllegalStateException.class, () -> f.model("").call(new Prompt("test"))); f.server.verify();
        }
        var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.model("none"));
    }
    @Test void retryUsesConfiguredMaximumAndRejectsNonTransientErrors() {
        assertThrows(IllegalArgumentException.class, () -> ResponsesConfiguration.retryTemplate(0, Duration.ofMillis(1)));
        for (RuntimeException error : List.of(new ResourceAccessException("network"), new RestClientResponseException("throttled",429,"",null,null,null), new RestClientResponseException("server",503,"",null,null,null), new RestClientResponseException("unauthorized",401,"",null,null,null), new IllegalArgumentException("bad"))) {
            AtomicInteger attempts = new AtomicInteger(); var retry = ResponsesConfiguration.retryTemplate(3, Duration.ofMillis(1));
            assertThrows(Exception.class, () -> retry.invoke(() -> {attempts.incrementAndGet(); throw error;}));
            boolean transientError = error instanceof ResourceAccessException || error instanceof RestClientResponseException r && (r.getStatusCode().value() == 429 || r.getStatusCode().is5xxServerError());
            assertEquals(transientError ? 3 : 1, attempts.get());
        }
        var f = new Fixture(); f.server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)); f.server.expect(anything()).andRespond(withSuccess(TEXT, MediaType.APPLICATION_JSON));
        assertEquals("hello", f.model("").call(new Prompt("test")).getResult().getOutput().getText()); f.server.verify();
    }
    @Test void configurationBuildsWithoutContactingProviderAndFixesTheModel() {
        var config = new ResponsesConfiguration(); var environment = new org.springframework.mock.env.MockEnvironment().withProperty("spring.ai.openai.api-key", "fake");
        assertEquals("gpt-6-astra", config.responsesChatModel(environment, "http://127.0.0.1:1/", "gpt-6-astra", "", Duration.ofSeconds(1), 1, Duration.ofMillis(1), 2, Duration.ofMillis(5)).getOptions().getModel());
        assertNotNull(config.responsesChatModel(environment, "http://127.0.0.1:1/v1", "gpt-6-astra", "", Duration.ofSeconds(1), 1, Duration.ofMillis(1), 2, Duration.ofMillis(5)));
        // AI-01: no selector or fallback model can be configured.
        assertThrows(IllegalArgumentException.class, () -> config.responsesChatModel(environment, "http://127.0.0.1:1", "gpt-4o", "", Duration.ofSeconds(1), 1, Duration.ofMillis(1), 2, Duration.ofMillis(5)));
        assertEquals(ResponsesChatModel.class, assertDoesNotThrow(() -> ResponsesChatModel.class.getMethod("stream", Prompt.class)).getDeclaringClass());
    }
    @Test void runtimeModelOptionCannotOverrideTheFixedModel() {
        var f = new Fixture();
        f.server.expect(requestTo("http://mock.invalid/v1/responses")).andExpect(request -> assertEquals("gpt-6-astra",
                JsonMapper.builder().build().readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()).path("model").asText()))
            .andRespond(withSuccess(TEXT, MediaType.APPLICATION_JSON));
        f.model("").call(new Prompt("hello", ToolCallingChatOptions.builder().model("gpt-4o").build())); f.server.verify();
    }
}
