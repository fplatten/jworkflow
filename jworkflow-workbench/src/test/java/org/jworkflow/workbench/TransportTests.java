package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.web.socket.*;

class TransportTests {
    @Test void validatesExactHostAndOrigin() {
        String host = "127.0.0.1:9876"; String origin = "http://" + host;
        assertTrue(LocalRequestPolicy.allowed(host, origin, 9876, true));
        assertTrue(LocalRequestPolicy.allowed(host, null, 9876, false));
        assertFalse(LocalRequestPolicy.allowed(host, null, 9876, true));
        assertFalse(LocalRequestPolicy.allowed("localhost:9876", origin, 9876, false));
        for (String invalid : new String[]{"https://" + host, "http://evil:9876", "http://127.0.0.1:9877", "http://u@" + host, origin + "/", origin + "?x=1", origin + "#fragment", "bad origin"})
            assertFalse(LocalRequestPolicy.allowed(host, invalid, 9876, true), invalid);
    }
    @Test void filterRejectsCsrfAndOversizeAndSetsSecurityHeaders() throws Exception {
        var filter = new LocalRequestFilter();
        for (String method : new String[]{"GET", "HEAD", "POST"}) {
            var req = new MockHttpServletRequest(method, "/"); req.setLocalPort(9876);
            req.addHeader("Host", "127.0.0.1:9876"); req.addHeader("Origin", "http://127.0.0.1:9876");
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            filter.doFilter(req, response, chain); assertNotNull(chain.getRequest());
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        }
        var request = new MockHttpServletRequest("POST", "/api/session"); request.setLocalPort(9876); request.addHeader("Host", "127.0.0.1:9876");
        var denied = new MockHttpServletResponse(); filter.doFilter(request, denied, new MockFilterChain()); assertEquals(403, denied.getStatus());
        request.addHeader("Origin", "http://127.0.0.1:9876"); request.setContent(new byte[8193]);
        var large = new MockHttpServletResponse(); filter.doFilter(request, large, new MockFilterChain()); assertEquals(413, large.getStatus());
        var upgrade = new MockHttpServletRequest("GET", "/terminal"); upgrade.setLocalPort(9876); upgrade.addHeader("Host", "127.0.0.1:9876"); upgrade.addHeader("Upgrade", "websocket");
        var rejected = new MockHttpServletResponse(); filter.doFilter(upgrade, rejected, new MockFilterChain()); assertEquals(403, rejected.getStatus());
    }
    @Test void bootstrapRejectsBadBodiesReplayAndUnavailablePreference(@TempDir Path temp) throws Exception {
        var auth = FoundationTests.session(Clock.systemUTC()); var preference = mock(LastProjectPreference.class);
        when(preference.read()).thenThrow(new java.io.IOException("unavailable")); var controller = new SessionController(auth, preference, "");
        assertEquals(413, controller.claim(body("x".repeat(8193))).getStatusCode().value());
        assertEquals(400, controller.claim(body("{" )).getStatusCode().value());
        assertEquals(403, controller.claim(body("{}")).getStatusCode().value());
        var claimed = controller.claim(body("{\"bootstrap\":\"" + auth.bootstrapToken() + "\"}"));
        assertEquals(200, claimed.getStatusCode().value()); assertEquals("", claimed.getBody().get("lastProject"));
        assertEquals(403, controller.claim(body("{\"bootstrap\":\"" + auth.bootstrapToken() + "\"}")).getStatusCode().value());
        assertNull(TerminalConfiguration.credential(null)); assertNull(TerminalConfiguration.credential("workbench"));
        assertEquals("token", TerminalConfiguration.credential("workbench, credential.token"));
    }
    @Test void launchArgumentSuggestsProjectWithoutReadingPreference() throws Exception {
        var auth = FoundationTests.session(Clock.systemUTC()); var preference = mock(LastProjectPreference.class);
        var controller = new SessionController(auth, preference, "/work/orders");
        var claimed = controller.claim(body("{\"bootstrap\":\"" + auth.bootstrapToken() + "\"}"));
        assertEquals("/work/orders", claimed.getBody().get("lastProject")); assertEquals("argument", claimed.getBody().get("projectSource"));
        verify(preference, never()).read();
    }
    @Test void launchArgumentsMapOneProjectDirectoryAndKeepOptions(@TempDir Path temp) {
        String[] mapped = WorkbenchApplication.launchArguments(new String[]{"--server.port=0", temp.resolve("a/../orders").toString()});
        assertArrayEquals(new String[]{"--server.port=0", "--workbench.project=" + temp.resolve("orders").toAbsolutePath()}, mapped);
        assertArrayEquals(new String[]{"--x=1"}, WorkbenchApplication.launchArguments(new String[]{"--x=1"}));
        assertThrows(IllegalArgumentException.class, () -> WorkbenchApplication.launchArguments(new String[]{"one", "two"}));
        assertThrows(IllegalArgumentException.class, () -> WorkbenchApplication.launchArguments(new String[]{" "}));
    }
    static MockHttpServletRequest body(String text) {
        var request = new MockHttpServletRequest(); request.setContent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return request;
    }
    static WebSocketSession connection(String id, String credential, java.util.List<String> sent) throws Exception {
        var socket = mock(WebSocketSession.class); when(socket.getId()).thenReturn(id); when(socket.isOpen()).thenReturn(true);
        var attrs = new HashMap<String,Object>(); attrs.put("credential", credential); when(socket.getAttributes()).thenReturn(attrs);
        doAnswer(invocation -> {sent.add(((TextMessage) invocation.getArgument(0)).getPayload()); return null;}).when(socket).sendMessage(any()); return socket;
    }
    /** WB-23 command matrix while a build runs: AI input rejected, /clear allowed, /cancel stops only the build, /exit stops it first. */
    @Test void terminalCommandMatrixDuringBuildOrTest() throws Exception {
        var auth = FoundationTests.session(Clock.systemUTC()); String credential = auth.claim(auth.bootstrapToken());
        var coordinator = new OperationCoordinator(); var cancelled = new java.util.concurrent.atomic.AtomicInteger(); var exits = new java.util.concurrent.atomic.AtomicInteger();
        var shell = new FoundationShell(exits::incrementAndGet, () -> "Deleted the current conversation history.");
        var sent = new CopyOnWriteArrayList<String>(); var owner = connection("owner", credential, sent);
        try (var handler = new TerminalSocket(auth, shell, null, coordinator, null)) {
            handler.afterConnectionEstablished(owner);
            var build = coordinator.tryBegin(OperationCoordinator.Kind.BUILD, cancelled::incrementAndGet);
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"please help\"}"));
            assertTrue(sent.get(sent.size() - 1).contains("A /build is running; the AI request was not queued."));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"/clear\"}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (sent.stream().noneMatch(s -> s.contains("Deleted the current conversation")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(sent.stream().anyMatch(s -> s.contains("Deleted the current conversation")), "/clear is not blocked by a build");
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"/cancel\"}"));
            assertEquals(1, cancelled.get()); assertTrue(sent.get(sent.size() - 1).contains("Stopping /build"));
            coordinator.end(build);
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"/cancel\"}"));
            assertTrue(sent.get(sent.size() - 1).contains("No active operation."));
            coordinator.tryBegin(OperationCoordinator.Kind.TEST, cancelled::incrementAndGet);
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"/exit\"}"));
            assertEquals(2, cancelled.get(), "/exit stops the running test tree first"); assertEquals(1, exits.get());
        }
    }

    @Test void socketRejectsSecondOwnerAndValidatesFramesWhileWorkIsActive() throws Exception {
        var auth = FoundationTests.session(Clock.systemUTC()); String credential = auth.claim(auth.bootstrapToken());
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var shell = new FoundationShell(() -> {}, input -> {
            started.countDown(); try {release.await(3, TimeUnit.SECONDS);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
            finally {finished.countDown();} return "done";
        });
        var sent = new CopyOnWriteArrayList<String>(); var owner = connection("owner", credential, sent);
        try (var handler = new TerminalSocket(auth, shell)) {
            assertEquals(java.util.List.of("workbench"), handler.getSubProtocols()); handler.afterConnectionEstablished(owner);
            var second = connection("second", credential, new CopyOnWriteArrayList<>()); handler.afterConnectionEstablished(second);
            verify(second).close(argThat(status -> status.getCode() == 1008)); handler.afterConnectionClosed(second, CloseStatus.NORMAL);
            handler.handleTextMessage(second, new TextMessage("{}"));
            handler.handleTextMessage(owner, new TextMessage("{")); handler.handleTextMessage(owner, new TextMessage("{}"));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"resize\",\"columns\":80,\"rows\":25}"));
            assertEquals(80, owner.getAttributes().get("columns"));
            for (int[] size : new int[][]{{1,25},{501,25},{80,1},{80,201}})
                handler.handleTextMessage(owner, new TextMessage("{\"type\":\"resize\",\"columns\":" + size[0] + ",\"rows\":" + size[1] + "}"));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"" + "x".repeat(4097) + "\"}"));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"slow\"}")); assertTrue(started.await(2, TimeUnit.SECONDS));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"second\"}"));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"heartbeat\"}"));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"/cancel\"}"));
            assertTrue(sent.stream().anyMatch(value -> value.contains("cancelled")));
            handler.handleTextMessage(owner, new TextMessage("{\"type\":\"input\",\"text\":\"/exit\"}"));
            assertTrue(sent.stream().anyMatch(value -> value.contains("not queued")));
            assertTrue(sent.stream().anyMatch(value -> value.contains("heartbeat")));
            assertTrue(sent.stream().anyMatch(value -> value.contains("shutting down")));
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS));
            handler.handleTextMessage(owner, new TextMessage("x".repeat(8193))); verify(owner).close(CloseStatus.TOO_BIG_TO_PROCESS);
            handler.afterConnectionClosed(owner, CloseStatus.NORMAL);
        }
        verify(owner).close(CloseStatus.GOING_AWAY);
        try (var empty = new TerminalSocket(auth, shell)) {empty.handleTextMessage(owner, new TextMessage("{}"));}
    }
}
