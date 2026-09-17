package org.jworkflow.jdbc;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.*;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSignal;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.observability.WorkflowLifecycleEvent;
import org.jworkflow.observability.WorkflowLifecycleEventType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Verifies commit-aware lifecycle delivery using file-backed SQLite. */
public final class JdbcObservabilityContractTest {
    private JdbcObservabilityContractTest() {
    }

    public static void main(String[] args) throws Exception {
        Path database = Files.createTempFile("jworkflow-observability-", ".sqlite");
        try {
            List<WorkflowLifecycleEvent> observations = new CopyOnWriteArrayList<>();
            WorkflowDefinitionBuilder definition = WorkflowDefinitionBuilder.workflow("observable")
                    .version("1")
                    .startAt("work")
                    .step("work", step -> step.action("work.execute").onSuccess("done"))
                    .end("done");
            try (WorkflowEngine built = WorkflowEngine.builder()
                    .type(WorkflowEngine.Type.SQLITE)
                    .jdbcUrl("jdbc:sqlite:" + database)
                    .initialize()
                    .definition(definition)
                    .stepHandler("work.execute", context -> StepResult.success())
                    .lifecycleObserver(observations::add)
                    .timerPolling(false)
                    .build()) {
                JdbcWorkflowEngine engine = (JdbcWorkflowEngine) built;
                WorkflowInstanceId id = engine.start("observable", "business-1", Map.of());
                require(has(observations, WorkflowLifecycleEventType.WORKFLOW_STARTED),
                        "Committed start did not produce an observation");
                require(has(observations, WorkflowLifecycleEventType.OUTBOX_CREATED),
                        "Committed outbox enqueue did not produce an observation");

                observations.clear();
                engine.writeProbe(stage -> {
                    if ("event".equals(stage)) throw new IllegalStateException("injected rollback");
                });
                try {
                    engine.signal(id, signal(id));
                    throw new AssertionError("Expected injected rollback");
                } catch (IllegalStateException expected) {
                    require("injected rollback".equals(expected.getMessage()), "Unexpected failure: " + expected);
                }
                require(observations.isEmpty(), "Rolled-back mutation emitted lifecycle observations");
                require(engine.snapshot(id).status() == WorkflowStatus.RUNNING,
                        "Rolled-back mutation changed durable state");

                engine.writeProbe(null);
                engine.signal(id, signal(id));
                require(has(observations, WorkflowLifecycleEventType.WORKFLOW_COMPLETED),
                        "Committed transition did not produce completion observation");

                try (JdbcOutboxApplication outbox = engine.outbox(Map.of("workflow.events", ignored -> { }))) {
                    outbox.pollOnce();
                }
                require(has(observations, WorkflowLifecycleEventType.OUTBOX_PUBLISHED),
                        "Successful outbox publication was not observed");

                observations.clear();
                engine.start("observable", "business-2", Map.of());
                try (JdbcOutboxApplication outbox = engine.outbox(Map.of(
                        "workflow.events", ignored -> { throw new IllegalStateException("broker unavailable"); }))) {
                    outbox.pollOnce();
                }
                require(has(observations, WorkflowLifecycleEventType.OUTBOX_PUBLICATION_FAILED),
                        "Failed outbox publication was not observed");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static WorkflowSignal signal(WorkflowInstanceId id) {
        return new WorkflowSignal(
                "work.requested", "corr-1", null, "business-1", Instant.now(), Map.of());
    }

    private static boolean has(List<WorkflowLifecycleEvent> events, WorkflowLifecycleEventType type) {
        return events.stream().anyMatch(event -> event.type() == type);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
