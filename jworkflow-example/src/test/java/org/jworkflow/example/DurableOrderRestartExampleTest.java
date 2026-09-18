package org.jworkflow.example;

import org.jworkflow.engine.SignalWorkflowCommand;
import org.jworkflow.engine.StartWorkflowCommand;
import org.jworkflow.engine.WorkflowCommandMetadata;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.events.EventName;
import org.jworkflow.model.WaitDefinition;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.model.WorkflowSignal;
import org.jworkflow.model.WorkflowStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/** A genuine close/recreate example using typed commands and the same SQLite database file. */
public final class DurableOrderRestartExampleTest {
    public static void main(String[] args) throws Exception {
        Path database = Files.createTempFile("jworkflow-example-restart-", ".sqlite");
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath();
        WorkflowDefinition definition = WorkflowDefinition.of(
                "durable-order",
                "1.0.0",
                "awaiting-approval",
                WorkflowNode.waitFor("awaiting-approval",
                        new WaitDefinition(new EventName("order.approved"), "businessKey", "completed"), null),
                WorkflowNode.end("completed"));

        WorkflowInstanceId instanceId;
        try (WorkflowEngine first = durableEngine(jdbcUrl, definition)) {
            StartWorkflowCommand start = new StartWorkflowCommand(
                    "durable-order",
                    "1.0.0",
                    "order-restart-1001",
                    Map.of("order", Map.of("lines", Arrays.asList("sku-1", "sku-2"))),
                    metadata("create-order-restart-1001", null));
            instanceId = first.start(start).workflowInstanceId();
            check("awaiting-approval".equals(first.snapshot(instanceId).state())
                            && first.snapshot(instanceId).status() != WorkflowStatus.COMPLETED,
                    "The first process must durably stop at its event wait");
        }

        // No object from the first engine is reused: definitions and state are reconstructed from SQLite.
        try (WorkflowEngine restarted = durableEngine(jdbcUrl, null)) {
            check(restarted.snapshot(instanceId).variables().get("order") instanceof Map,
                    "Nested order data must survive restart");
            WorkflowSignal approved = new WorkflowSignal(
                    "order.approved", "order-restart-1001", null, "order-restart-1001", Instant.now(), Map.of());
            restarted.signal(new SignalWorkflowCommand(instanceId, approved,
                    metadata("approve-order-restart-1001", instanceId)));
            check(restarted.snapshot(instanceId).status() == WorkflowStatus.COMPLETED,
                    "The newly constructed engine must complete the original workflow");
        }
    }

    private static WorkflowEngine durableEngine(String jdbcUrl, WorkflowDefinition definition) throws Exception {
        var builder = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.SQLITE)
                .jdbcUrl(jdbcUrl)
                .initialize(true)
                .timerPolling(false);
        if (definition != null) builder.definition(definition);
        return builder.build();
    }

    private static WorkflowCommandMetadata metadata(String key, WorkflowInstanceId instanceId) {
        return new WorkflowCommandMetadata(null, key, "durable-order", "1.0.0", instanceId,
                "order-restart-1001", "order-restart-1001", null, null, null,
                "example-client", null, null, Map.of());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
