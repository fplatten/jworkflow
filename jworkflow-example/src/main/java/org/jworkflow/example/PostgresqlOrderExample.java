package org.jworkflow.example;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jworkflow.engine.*;
import org.jworkflow.events.EventMessage;
import org.jworkflow.events.EventName;
import org.jworkflow.inbox.*;
import org.jworkflow.jdbc.JdbcWorkflowEngine;
import org.jworkflow.model.*;

/** Environment-configured, separate-process PostgreSQL restart demonstration. */
public final class PostgresqlOrderExample {
    private PostgresqlOrderExample() { }

    /**
     * Runs init, start, resume, publish, or status in a pre-created dedicated schema.
     * @param args exactly one phase name
     * @throws Exception if configuration or persistence fails
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("init", "start", "resume", "publish", "status").contains(args[0])) {
            throw new IllegalArgumentException("Choose init, start, resume, publish, or status");
        }
        String phase = args[0];
        var builder = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                .jdbcUrl(required("JWORKFLOW_JDBC_URL")).username(required("JWORKFLOW_JDBC_USERNAME"))
                .password(required("JWORKFLOW_JDBC_PASSWORD"))
                .initialize("init".equals(phase)).timerPolling(false);
        if ("start".equals(phase)) {
            builder.definition(WorkflowDefinition.of("postgres-order", "1.0.0", "approval",
                    WorkflowNode.waitFor("approval", new WaitDefinition(new EventName("order.approved"),
                            "businessKey", "completed"), null), WorkflowNode.end("completed")));
        }
        try (var engine = (JdbcWorkflowEngine) builder.build()) {
            if ("init".equals(phase)) {
                System.out.println("Built-in migrations applied; no workflow started.");
                return;
            }
            if ("publish".equals(phase)) {
                try (var outbox = engine.outbox(Map.of("workflow.events",
                        message -> System.out.println("Published message " + message.messageId())))) {
                    System.out.println("Published count: " + outbox.pollOnce());
                }
                return;
            }
            // The stored command result supplies the same ID in every process after start.
            var result = engine.start(new StartWorkflowCommand("postgres-order", "1.0.0", "order-1001",
                    Map.of("lines", List.of("sku-1", "sku-2")), metadata("create-order-1001", null)));
            var id = result.workflowInstanceId();
            if ("start".equals(phase)) {
                try (var inbox = engine.inbox(message -> List.of())) {
                    inbox.accept(new InboxMessage(null, "approval-1001", "orders",
                            EventMessage.json(Map.of("approved", true)), "order-1001", null, Instant.EPOCH,
                            null, InboxMessageStatus.RECEIVED, 0, null, null, null, null));
                }
            } else if ("resume".equals(phase)) {
                try (var inbox = engine.inbox(message -> List.of(new SignalWorkflowCommand(id,
                        new WorkflowSignal("order.approved", message.correlationId(), message.causationId(),
                                "order-1001", message.receivedAt(), Map.of(), message.message()),
                        metadata("approve-order-1001", id))))) {
                    System.out.println("Processed inbox count: " + inbox.pollOnce());
                }
            }
            var snapshot = engine.snapshot(id);
            System.out.println("Instance: " + id + "; status: " + snapshot.status()
                    + "; revision: " + snapshot.workflowRevision() + "; replay: " + result.idempotentRepeat());
            System.out.println("Timeline entries: " + engine.queries().timeline(id).entries().size());
        }
    }

    private static WorkflowCommandMetadata metadata(String key, WorkflowInstanceId id) {
        return new WorkflowCommandMetadata(null, key, "postgres-order", "1.0.0", id, "order-1001",
                "order-1001", null, null, null, "example", null, null, Map.of());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
