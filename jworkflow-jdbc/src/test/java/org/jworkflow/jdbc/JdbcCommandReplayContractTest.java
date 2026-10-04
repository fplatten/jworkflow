package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventName;
import org.jworkflow.model.*;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

/**
 * An idempotent repeat returns the result recorded when the command first ran, on every backend, even after the
 * workflow has moved on.
 */
public final class JdbcCommandReplayContractTest {
    public static void main(String[] args) throws Exception {
        repeatedStartReturnsTheCommandTimeSnapshot();
        repeatedSignalReturnsTheCommandTimeSnapshot();
    }

    private static void repeatedStartReturnsTheCommandTimeSnapshot() throws Exception {
        String url = url("start");
        try (JdbcWorkflowEngine engine = engine(url)) {
            StartWorkflowResult first = engine.start(start("replay-start", "order-s"));
            engine.signal(signal(first.workflowInstanceId(), "first.approved", "replay-start-a", "order-s"));
            engine.signal(signal(first.workflowInstanceId(), "second.approved", "replay-start-b", "order-s"));
            check(engine.snapshot(first.workflowInstanceId()).status() == WorkflowStatus.COMPLETED, "workflow must complete");

            StartWorkflowResult repeat = engine.start(start("replay-start", "order-s"));
            check(repeat.idempotentRepeat(), "second start with the same key must be a repeat");
            check(repeat.commandId().equals(first.commandId()), "repeat must return the original command id");
            check(repeat.snapshot().equals(first.snapshot()),
                    "repeat must return the start-time snapshot, not the completed one: " + repeat.snapshot().state());
            check(repeat.emittedEventIds().equals(first.emittedEventIds()), "repeat must return the original event ids");
        }
    }

    private static void repeatedSignalReturnsTheCommandTimeSnapshot() throws Exception {
        String url = url("signal");
        WorkflowInstanceId id;
        try (JdbcWorkflowEngine engine = engine(url)) {
            id = engine.start(start("replay-signal", "order-g")).workflowInstanceId();
            WorkflowCommandResult first = engine.signal(signal(id, "first.approved", "replay-signal-a", "order-g"));
            check("second".equals(first.snapshot().state()), "first signal must move to the second wait");
            engine.signal(signal(id, "second.approved", "replay-signal-b", "order-g"));

            WorkflowCommandResult repeat = engine.signal(signal(id, "first.approved", "replay-signal-a", "order-g"));
            check(repeat.idempotentRepeat(), "second signal with the same key must be a repeat");
            check(repeat.status() == first.status(), "repeat must return the original status");
            check(repeat.snapshot().equals(first.snapshot()),
                    "repeat must return the signal-time snapshot, not the completed one: " + repeat.snapshot().state());
            check(repeat.emittedEventIds().equals(first.emittedEventIds()), "repeat must return the original event ids");
        }
        try (JdbcWorkflowEngine restarted = engine(url)) {
            WorkflowCommandResult repeat = restarted.signal(signal(id, "first.approved", "replay-signal-a", "order-g"));
            check(repeat.idempotentRepeat() && "second".equals(repeat.snapshot().state()),
                    "the command-time snapshot must survive an engine restart");
        }
    }

    private static WorkflowDefinition definition() {
        return WorkflowDefinition.of("replay-flow", "1", "first",
                WorkflowNode.waitFor("first", new WaitDefinition(new EventName("first.approved"), "businessKey", "second"), null),
                WorkflowNode.waitFor("second", new WaitDefinition(new EventName("second.approved"), "businessKey", "done"), null),
                WorkflowNode.end("done"));
    }
    private static StartWorkflowCommand start(String key, String business) {
        return new StartWorkflowCommand("replay-flow", "1", business, Map.of(), metadata(key, null, business));
    }
    private static SignalWorkflowCommand signal(WorkflowInstanceId id, String event, String key, String business) {
        return new SignalWorkflowCommand(id, new WorkflowSignal(event, "corr-" + business, null, business,
                Instant.parse("2026-01-01T00:00:00Z"), Map.of()), metadata(key, id, business));
    }
    private static WorkflowCommandMetadata metadata(String key, WorkflowInstanceId id, String business) {
        return new WorkflowCommandMetadata(null, key, "replay-flow", "1", id, business, "corr-" + business, null, null,
                null, "test", null, null, Map.of());
    }
    private static String url(String name) throws Exception {
        return ContractBackend.url(Files.createTempFile("jworkflow-replay-" + name + "-", ".sqlite").toAbsolutePath());
    }
    private static JdbcWorkflowEngine engine(String url) {
        return (JdbcWorkflowEngine) ContractBackend.engine(url).initialize(true).timerPolling(false).definition(definition()).build();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
