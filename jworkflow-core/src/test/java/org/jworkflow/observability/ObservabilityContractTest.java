package org.jworkflow.observability;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.*;
import org.jworkflow.events.EventMetadata;
import org.jworkflow.events.EventMessage;
import org.jworkflow.events.EventName;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSignal;
import org.jworkflow.model.WorkflowStatus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ObservabilityContractTest {
    private ObservabilityContractTest() {
    }

    public static void main(String[] args) throws Exception {
        observesWorkflowLifecycleAndMetrics();
        isolatesObserverFailures();
        logsCorrelationAndTraceMetadata();
    }

    private static void observesWorkflowLifecycleAndMetrics() throws Exception {
        List<WorkflowLifecycleEvent> observed = new CopyOnWriteArrayList<>();
        RecordingMetrics metrics = new RecordingMetrics();
        WorkflowLifecycleObserver observer = CompositeWorkflowLifecycleObserver.of(
                observed::add, new MetricsWorkflowLifecycleObserver(metrics));

        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition())
                .stepHandler("employee.verify", context -> StepResult.success())
                .lifecycleObserver(observer)
                .build()) {
            WorkflowCommandMetadata metadata = new WorkflowCommandMetadata(null, "start-1", "onboarding", "1",
                    null, "employee-7", "corr-7", "cause-7", "trace-7", null, "test", null, null, Map.of());
            WorkflowInstanceId id = engine.start(new StartWorkflowCommand(
                    "onboarding", "1", "employee-7", Map.of(), metadata)).workflowInstanceId();
            engine.signal(id, new WorkflowSignal(
                    "employee.submitted", "corr-7", "cause-8", "employee-7", Instant.now(), Map.of()));
            require(engine.snapshot(id).status() == WorkflowStatus.COMPLETED, "Workflow did not complete");
        }

        WorkflowLifecycleEvent started = one(observed, WorkflowLifecycleEventType.WORKFLOW_STARTED);
        require("onboarding".equals(started.workflowKey()), "Workflow key missing from lifecycle event");
        require("1".equals(started.workflowVersion()), "Workflow version missing from lifecycle event");
        require("corr-7".equals(started.correlationId()), "Correlation ID missing from lifecycle event");
        require("trace-7".equals(started.traceId()), "Trace ID missing from lifecycle event");
        one(observed, WorkflowLifecycleEventType.STEP_COMPLETED);
        one(observed, WorkflowLifecycleEventType.TRANSITION_TAKEN);
        one(observed, WorkflowLifecycleEventType.WORKFLOW_COMPLETED);
        require(metrics.count("jworkflow.workflow.started") == 1, "Start metric missing");
        require(metrics.count("jworkflow.workflow.completed") == 1, "Completion metric missing");
        for (WorkflowLifecycleEventType type : List.of(
                WorkflowLifecycleEventType.WORKFLOW_FAILED,
                WorkflowLifecycleEventType.STEP_FAILED,
                WorkflowLifecycleEventType.RETRY_SCHEDULED,
                WorkflowLifecycleEventType.OUTBOX_PUBLISHED,
                WorkflowLifecycleEventType.OUTBOX_PUBLICATION_FAILED,
                WorkflowLifecycleEventType.INBOX_DEAD_LETTERED)) {
            new MetricsWorkflowLifecycleObserver(metrics).observe(new WorkflowLifecycleEvent(
                    type, Instant.now(), null, "onboarding", "1", null, "verify",
                    "sensitive-correlation", null, null, Map.of("destination", "events")));
        }
        require(metrics.count("jworkflow.workflow.failed") == 1, "Failure metric missing");
        require(metrics.count("jworkflow.retry.scheduled") == 1, "Retry metric missing");
        require(metrics.count("jworkflow.outbox.published") == 1, "Outbox success metric missing");
        require(metrics.count("jworkflow.outbox.failed") == 1, "Outbox failure metric missing");
        require(metrics.count("jworkflow.inbox.dead_lettered") == 1, "Inbox dead-letter metric missing");
        metrics.tags.values().forEach(tags -> {
            require(!tags.containsKey("correlationId"), "High-cardinality correlation ID used as a metric tag");
            require(!tags.containsKey("workflowInstanceId"), "Workflow instance ID used as a metric tag");
        });
    }

    private static void isolatesObserverFailures() throws Exception {
        List<WorkflowLifecycleEvent> surviving = new ArrayList<>();
        WorkflowLifecycleObserver observers = CompositeWorkflowLifecycleObserver.of(
                new SafeWorkflowLifecycleObserver(
                        event -> { throw new IllegalStateException("telemetry unavailable"); },
                        (event, failure) -> { }),
                surviving::add);
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(WorkflowDefinitionBuilder.workflow("observer-failure")
                        .version("1").startAt("done").end("done"))
                .lifecycleObserver(observers)
                .build()) {
            WorkflowInstanceId id = engine.start("observer-failure", "business-1", Map.of());
            require(engine.snapshot(id).status() == WorkflowStatus.COMPLETED,
                    "Observer failure changed workflow outcome");
        }
        require(!surviving.isEmpty(), "One failing observer prevented later observers");
    }

    private static void logsCorrelationAndTraceMetadata() {
        RecordingLogger logger = new RecordingLogger();
        WorkflowLifecycleEvent event = new WorkflowLifecycleEvent(
                WorkflowLifecycleEventType.EVENT_RECEIVED, Instant.now(), null, "onboarding", "1", "wait",
                null, "corr-log", "cause-log", "trace-log", Map.of("eventName", "tax.completed"));
        new SystemLoggerWorkflowLifecycleObserver(logger).observe(event);
        require(logger.message.contains("correlationId=corr-log"), "Structured log omitted correlation ID");
        require(logger.message.contains("traceId=trace-log"), "Structured log omitted trace ID");
    }

    private static WorkflowDefinitionBuilder definition() {
        return WorkflowDefinitionBuilder.workflow("onboarding")
                .version("1")
                .startAt("verify")
                .step("verify", step -> step.action("employee.verify").onSuccess("done"))
                .end("done");
    }

    private static WorkflowLifecycleEvent one(List<WorkflowLifecycleEvent> events, WorkflowLifecycleEventType type) {
        return events.stream().filter(event -> event.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("Missing lifecycle event " + type + ": " + events));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingMetrics implements WorkflowMetrics {
        private final Map<String, Integer> counts = new HashMap<>();
        private final Map<String, Map<String, String>> tags = new HashMap<>();
        @Override public void increment(String metric, Map<String, String> metricTags) {
            counts.merge(metric, 1, Integer::sum);
            tags.put(metric, metricTags);
        }
        int count(String metric) { return counts.getOrDefault(metric, 0); }
    }

    private static final class RecordingLogger implements System.Logger {
        private String message = "";
        @Override public String getName() { return "test"; }
        @Override public boolean isLoggable(Level level) { return true; }
        @Override public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) { message = msg; }
        @Override public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            message = format;
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
