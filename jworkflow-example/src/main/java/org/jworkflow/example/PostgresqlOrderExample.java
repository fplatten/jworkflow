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

/**
 * Environment-configured, separate-process PostgreSQL restart demonstration.
 * Progress and publication messages are written at INFO level through {@link System.Logger}.
 */
public final class PostgresqlOrderExample {
    private static final System.Logger LOGGER = System.getLogger(PostgresqlOrderExample.class.getName());
    private static final String START = "start";
    private static final String WORKFLOW_KEY = "postgres-order";
    private static final String VERSION = "1.0.0";
    private static final String ORDER_ID = "order-1001";

    private PostgresqlOrderExample() { }

    /**
     * Runs init, start, resume, publish, or status in a pre-created dedicated schema.
     * Reads connection settings from the JWORKFLOW_JDBC_URL, JWORKFLOW_JDBC_USERNAME and
     * JWORKFLOW_JDBC_PASSWORD environment variables. Run init and start before the later phases.
     * @param args exactly one phase name
     * @throws Exception if the required JDBC driver cannot be loaded or configuration or persistence fails
     * @throws IllegalArgumentException if the phase is invalid or a required environment variable is missing or blank
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("init", START, "resume", "publish", "status").contains(args[0])) {
            throw new IllegalArgumentException("Choose init, start, resume, publish, or status");
        }
        String phase = args[0];
        var builder = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                .jdbcUrl(required("JWORKFLOW_JDBC_URL")).username(required("JWORKFLOW_JDBC_USERNAME"))
                .password(required("JWORKFLOW_JDBC_PASSWORD"));
        run(phase, builder);
    }

    /**
     * Runs one phase using a supplied JDBC configuration, closing the engine before returning.
     * Mutates the builder to initialize only during init and to disable background timer polling;
     * start also registers the example definition. Resume and status replay the original start command
     * to recover its instance ID. Each invocation should receive a fresh builder for the same database.
     * @param phase previously validated init, start, resume, publish, or status phase
     * @param builder configured JDBC engine builder; tests may supply SQLite to exercise the same phases
     * @throws ClassNotFoundException if the selected backend's required JDBC driver cannot be loaded
     */
    static void run(String phase, WorkflowEngineBuilder builder) throws ClassNotFoundException {
        builder.initialize("init".equals(phase)).timerPolling(false);
        if (START.equals(phase)) {
            builder.definition(WorkflowDefinition.of(WORKFLOW_KEY, VERSION, "approval",
                    WorkflowNode.waitFor("approval", new WaitDefinition(new EventName("order.approved"),
                            "businessKey", "completed"), null), WorkflowNode.end("completed")));
        }
        try (var engine = (JdbcWorkflowEngine) builder.build()) {
            if ("init".equals(phase)) {
                LOGGER.log(System.Logger.Level.INFO, "Built-in migrations applied; no workflow started.");
                return;
            }
            if ("publish".equals(phase)) {
                try (var outbox = engine.outbox(Map.of("workflow.events",
                        message -> LOGGER.log(System.Logger.Level.INFO, "Published message " + message.messageId())))) {
                    LOGGER.log(System.Logger.Level.INFO, "Published count: " + outbox.pollOnce());
                }
                return;
            }
            // The stored command result supplies the same ID in every process after start.
            var result = engine.start(new StartWorkflowCommand(WORKFLOW_KEY, VERSION, ORDER_ID,
                    Map.of("lines", List.of("sku-1", "sku-2")), metadata("create-order-1001", null)));
            var id = result.workflowInstanceId();
            if (START.equals(phase)) {
                try (var inbox = engine.inbox(message -> List.of())) {
                    inbox.accept(new InboxMessage(null, "approval-1001", "orders",
                            EventMessage.json(Map.of("approved", true)), ORDER_ID, null, Instant.EPOCH,
                            null, InboxMessageStatus.RECEIVED, 0, null, null, null, null));
                }
            } else if ("resume".equals(phase)) {
                try (var inbox = engine.inbox(message -> List.of(new SignalWorkflowCommand(id,
                        new WorkflowSignal("order.approved", message.correlationId(), message.causationId(),
                                ORDER_ID, message.receivedAt(), Map.of(), message.message()),
                        metadata("approve-order-1001", id))))) {
                    LOGGER.log(System.Logger.Level.INFO, "Processed inbox count: " + inbox.pollOnce());
                }
            }
            var snapshot = engine.snapshot(id);
            LOGGER.log(System.Logger.Level.INFO, "Instance: " + id + "; status: " + snapshot.status()
                    + "; revision: " + snapshot.workflowRevision() + "; replay: " + result.idempotentRepeat());
            LOGGER.log(System.Logger.Level.INFO, "Timeline entries: " + engine.queries().timeline(id).entries().size());
        }
    }

    private static WorkflowCommandMetadata metadata(String key, WorkflowInstanceId id) {
        return new WorkflowCommandMetadata(null, key, WORKFLOW_KEY, VERSION, id, ORDER_ID,
                ORDER_ID, null, null, null, "example", null, null, Map.of());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
