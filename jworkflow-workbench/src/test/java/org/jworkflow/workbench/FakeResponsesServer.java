package org.jworkflow.workbench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/** Local scripted stand-in for the OpenAI Responses endpoint; tests never contact a real provider. */
final class FakeResponsesServer implements AutoCloseable {
    @FunctionalInterface interface Script { void respond(HttpExchange exchange) throws Exception; }

    final HttpServer server;
    final BlockingQueue<Script> scripts = new LinkedBlockingQueue<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final CountDownLatch clientGone = new CountDownLatch(1);

    FakeResponsesServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/responses", exchange -> {
            try {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                Script script = scripts.poll(5, TimeUnit.SECONDS);
                if (script == null) { exchange.sendResponseHeaders(500, -1); return; }
                script.respond(exchange);
            } catch (Exception failure) { /* The client went away or the script ended the exchange. */ }
            finally { exchange.close(); }
        });
        server.start();
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static Script status(int code, String body) {
        return exchange -> { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); };
    }

    /** Streams the given SSE data payloads, optionally without the terminating completed event. */
    static Script sse(String... events) {
        return exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (String event : events) { out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
        };
    }

    /** Streams one delta, then keeps the connection open, writing comments until the client disconnects. */
    Script hold(String delta) {
        return exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(("data: " + delta(delta) + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            try { for (int i = 0; i < 600; i++) { Thread.sleep(100); out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); } }
            catch (IOException closed) { clientGone.countDown(); }
        };
    }

    static String delta(String text) {
        return "{\"type\":\"response.output_text.delta\",\"delta\":" + quote(text) + "}";
    }

    static String completed(String text, String toolArguments) {
        StringBuilder output = new StringBuilder("[");
        if (text != null) output.append("{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":").append(quote(text)).append("}]}");
        if (toolArguments != null) output.append(text != null ? "," : "").append("{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"propose_workflow_edit\",\"arguments\":").append(quote(toolArguments)).append('}');
        output.append(']');
        return "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\",\"model\":\"gpt-6-astra\",\"status\":\"completed\",\"output\":" + output
                + ",\"usage\":{\"input_tokens\":5,\"output_tokens\":3,\"total_tokens\":8}}}";
    }

    static String quote(String value) { return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value); }

    @Override public void close() { server.stop(0); }
}
