package org.jworkflow.workbench;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Future;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Authenticated bounded terminal transport; control input is independent of operation execution. */
public final class TerminalSocket extends TextWebSocketHandler implements SubProtocolCapable, AutoCloseable {
    private final SessionAuthority authority;
    private final FoundationShell shell;
    private final AssistantService assistant;
    private final OperationCoordinator coordinator;
    private final CommandService commands;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService operations = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> active;
    private volatile WebSocketSession owner;

    public TerminalSocket(SessionAuthority authority, FoundationShell shell) { this(authority, shell, null, new OperationCoordinator(), null); }

    public TerminalSocket(SessionAuthority authority, FoundationShell shell, AssistantService assistant, OperationCoordinator coordinator, CommandService commands) {
        this.authority = authority;
        this.shell = shell;
        this.assistant = assistant;
        this.coordinator = coordinator;
        this.commands = commands;
        if (commands != null) commands.listener(commandListener());
    }

    @Override public List<String> getSubProtocols() { return List.of("workbench"); }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        String token = (String) session.getAttributes().get("credential");
        if (!authority.attach(token, session.getId())) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Workbench already open or session expired"));
            return;
        }
        session.setTextMessageSizeLimit(8192);
        owner = new ConcurrentWebSocketSessionDecorator(session, 2000, 16384);
        send(owner, "ready", "JWorkflow Workbench — browser terminal foundation. Use /help.");
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        WebSocketSession target = owner;
        if (target == null || !authority.touch(session.getId())) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        if (message.getPayloadLength() > 8192) { session.close(CloseStatus.TOO_BIG_TO_PROCESS); return; }
        JsonNode frame;
        try { frame = json.readTree(message.getPayload()); }
        catch (RuntimeException invalid) { send(target, "error", "Invalid JSON frame."); return; }
        String type = frame.path("type").asText();
        switch (type) {
            case "heartbeat" -> send(target, "heartbeat", "ok");
            case "resize" -> {
                int columns = frame.path("columns").asInt();
                int rows = frame.path("rows").asInt();
                if (columns < 10 || columns > 500 || rows < 2 || rows > 200) send(target, "error", "Invalid terminal size.");
                else { session.getAttributes().put("columns", columns); session.getAttributes().put("rows", rows); }
            }
            case "input" -> {
                String input = frame.path("text").asText();
                if (input.length() > 4096) { send(target, "error", "Input exceeds limit."); return; }
                String text = input.strip();
                OperationCoordinator.Kind running = coordinator.active();
                boolean process = running == OperationCoordinator.Kind.BUILD || running == OperationCoordinator.Kind.TEST;
                if (text.equals("/cancel")) {
                    if (process) {
                        // Build/test cancellation stops only the process tree; conversation and sources are untouched.
                        coordinator.cancelActive();
                        send(target, "output", "Stopping /" + running.name().toLowerCase(java.util.Locale.ROOT) + " and every process it started…");
                    } else if (busy.compareAndSet(true, false)) {
                        generation.incrementAndGet();
                        boolean aiCancelled = assistant != null && assistant.cancel();
                        Future<?> operation = active;
                        if (operation != null) operation.cancel(true);
                        send(target, "output", aiCancelled ? "AI request cancelled. The current conversation was deleted and a new one started; pending AI proposals were discarded."
                                : "Active operation cancelled.");
                    } else send(target, "output", shell.evaluate(input));
                } else if (text.equals("/clear") && (busy.get() || running == OperationCoordinator.Kind.AI)) {
                    send(target, "error", "An operation is active. Use /cancel first; nothing was cleared.");
                } else if (text.equals("/exit")) {
                    // /exit stays responsive: children are stopped before shutdown; an AI request is abandoned without deleting history.
                    if (process) coordinator.cancelActive();
                    if (busy.compareAndSet(true, false)) {
                        generation.incrementAndGet();
                        if (assistant != null) assistant.stopForShutdown();
                        Future<?> operation = active;
                        if (operation != null) operation.cancel(true);
                    }
                    send(target, "output", shell.evaluate(input));
                } else if ((text.equals("/build") || text.equals("/test")) && commands != null) {
                    try {
                        var proposal = commands.propose(text.equals("/build") ? BuildTools.Kind.BUILD : BuildTools.Kind.TEST);
                        frame(target, Map.of("type", "command", "proposal", proposal));
                        send(target, "output", text + " is ready for review: " + proposal.tool() + " " + proposal.toolVersion() + " in " + proposal.workingDirectory()
                                + ". Approve the exact command in the Build and test panel; nothing runs until you do.");
                    } catch (IllegalStateException | IllegalArgumentException | IOException refused) { send(target, "output", refused.getMessage()); }
                } else if (process && !text.isEmpty() && !text.startsWith("/")) {
                    send(target, "error", "A /" + running.name().toLowerCase(java.util.Locale.ROOT) + " is running; the AI request was not queued.");
                } else if (!busy.compareAndSet(false, true)) {
                    send(target, "error", "An operation is active; input was not queued.");
                } else {
                    long operation = generation.incrementAndGet();
                    active = operations.submit(() -> {
                        OperationCoordinator.Ticket ticket = null;
                        try {
                            if (authority.touch(session.getId())) {
                                if (assistant != null && !text.isEmpty() && !text.startsWith("/")) {
                                    ticket = coordinator.tryBegin(OperationCoordinator.Kind.AI, () -> { });
                                    if (ticket == null) send(target, "error", "Another operation is active; the AI request was not queued.");
                                    else assistant.ask(text, output(target, operation));
                                } else {
                                    String output = shell.evaluate(input);
                                    if (generation.get() == operation) send(target, "output", output);
                                }
                            }
                        }
                        catch (IOException disconnected) { authority.detach(session.getId()); }
                        finally { coordinator.end(ticket); if (generation.get() == operation) busy.set(false); }
                    });
                }
            }
            default -> send(target, "error", "Unknown terminal frame.");
        }
    }

    /** Live build/test output and the final outcome go to whoever owns the terminal now; nothing is replayed later. */
    private CommandService.Listener commandListener() {
        return new CommandService.Listener() {
            @Override public void output(String text) { WebSocketSession current = owner; if (current != null) frame(current, Map.of("type", "delta", "text", text)); }
            @Override public void finished(CommandService.Result result) {
                WebSocketSession current = owner;
                if (current == null) return;
                frame(current, Map.of("type", "command-result", "result", result));
                frame(current, Map.of("type", "output", "text", result.message()));
            }
        };
    }

    private void frame(WebSocketSession target, Map<String, Object> value) {
        try { target.sendMessage(new TextMessage(json.writeValueAsString(value))); }
        catch (IOException | IllegalStateException disconnected) { /* The owner reconnects or the session ends; nothing is replayed. */ }
    }

    /** Terminal frames for one assistant request; output from a superseded (cancelled) request is dropped. */
    private AssistantService.Output output(WebSocketSession target, long operation) {
        return new AssistantService.Output() {
            @Override public void delta(String text) { frame(Map.of("type", "delta", "text", text)); }
            @Override public void line(String text) { frame(Map.of("type", "output", "text", text)); }
            @Override public void proposal(WorkflowEditProposals.Proposal proposal) { frame(Map.of("type", "proposal", "proposal", proposal)); }
            private void frame(Map<String, Object> value) {
                if (generation.get() != operation) return;
                try { target.sendMessage(new TextMessage(json.writeValueAsString(value))); }
                catch (IOException | IllegalStateException disconnected) { /* The owner reconnects or the session ends; nothing is replayed. */ }
            }
        };
    }

    private void send(WebSocketSession session, String type, String text) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(Map.of("type", type, "text", text))));
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        authority.detach(session.getId());
    }

    @Override public void close() throws IOException {
        generation.incrementAndGet();
        operations.shutdownNow();
        WebSocketSession current = owner;
        if (current != null && current.isOpen()) current.close(CloseStatus.GOING_AWAY);
    }
}
