package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;

class BrowserConnectionTests {
    static class Frames implements WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>(); final StringBuilder parts = new StringBuilder();
        public void onOpen(WebSocket socket) {socket.request(1);}
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            parts.append(data); if (last) {frames.add(parts.toString()); parts.setLength(0);} socket.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {closed.complete(status); return null;}
        String next() throws Exception {String frame = frames.poll(5, TimeUnit.SECONDS); assertNotNull(frame, "No terminal response"); return frame;}
    }
    @Test void realHttpAndWebSocketRequireAuthAndSurviveRefresh(@TempDir Path temp) throws Exception {
        try (var app = SpringApplication.run(WorkbenchApplication.class, "--workbench.browser.enabled=false", "--workbench.preference-file=" + temp.resolve("missing"), "--spring.ai.openai.api-key=")) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort(); String origin = "http://127.0.0.1:" + port;
            try (var http = HttpClient.newHttpClient()) {
                var page = http.send(HttpRequest.newBuilder(URI.create(origin)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode()); assertTrue(page.body().contains("JWorkflow Workbench"));
                assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElseThrow());
                var denied = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/session")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()); assertEquals(403, denied.statusCode());
                assertThrows(CompletionException.class, () -> http.newWebSocketBuilder().header("Origin", origin).subprotocols("workbench").buildAsync(URI.create("ws://127.0.0.1:" + port + "/terminal"), new Frames()).join());
                var authority = app.getBean(SessionAuthority.class);
                var claim = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/session")).header("Origin", origin).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"bootstrap\":\"" + authority.bootstrapToken() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, claim.statusCode()); String token = JsonMapper.builder().build().readTree(claim.body()).path("credential").asText();
                Frames frames = new Frames(); var socket = connect(http, origin, token, frames); assertTrue(frames.next().contains("ready"));
                socket.sendText("{\"type\":\"input\",\"text\":\"/core\"}", true).join(); assertTrue(frames.next().contains("org.jworkflow.application.Command"));
                Frames second = new Frames(); var rejected = connect(http, origin, token, second); assertEquals(1008, second.closed.get(5, TimeUnit.SECONDS));
                socket.sendText("{\"type\":\"input\",\"text\":\"exit criteria 漢字\"}", true).join(); assertTrue(frames.next().contains("Confirm a project before using the assistant"));
                socket.sendClose(1000, "refresh").join(); frames.closed.get(5, TimeUnit.SECONDS);
                Frames refreshed = new Frames(); var replacement = connect(http, origin, token, refreshed); assertTrue(refreshed.next().contains("ready"));
                replacement.sendText("{\"type\":\"input\",\"text\":\"/help\"}", true).join(); assertTrue(refreshed.next().contains("/exit"));
                replacement.sendClose(1000, "test finished").join();
            }
        }
    }
    static WebSocket connect(HttpClient client, String origin, String token, Frames listener) throws Exception {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).header("Origin", origin).subprotocols("workbench", "credential." + token).buildAsync(URI.create(origin.replace("http:", "ws:") + "/terminal"), listener).get(5, TimeUnit.SECONDS);
    }
}
