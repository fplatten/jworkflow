package org.jworkflow.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MetricsWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    private final WorkflowMetrics metrics;

    public MetricsWorkflowLifecycleObserver(WorkflowMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void observe(WorkflowLifecycleEvent event) {
        String metric = switch (event.type()) {
            case WORKFLOW_STARTED -> "jworkflow.workflow.started";
            case WORKFLOW_COMPLETED -> "jworkflow.workflow.completed";
            case WORKFLOW_FAILED -> "jworkflow.workflow.failed";
            case STEP_FAILED -> "jworkflow.step.failed";
            case RETRY_SCHEDULED -> "jworkflow.retry.scheduled";
            case OUTBOX_PUBLISHED -> "jworkflow.outbox.published";
            case OUTBOX_PUBLICATION_FAILED -> "jworkflow.outbox.failed";
            case INBOX_DEAD_LETTERED -> "jworkflow.inbox.dead_lettered";
            default -> null;
        };
        if (metric != null) metrics.increment(metric, lowCardinalityTags(event));
    }

    private static Map<String, String> lowCardinalityTags(WorkflowLifecycleEvent event) {
        LinkedHashMap<String, String> tags = new LinkedHashMap<>();
        put(tags, "workflow", event.workflowKey());
        put(tags, "version", event.workflowVersion());
        put(tags, "step", event.step());
        put(tags, "destination", event.attributes().get("destination"));
        put(tags, "failureCategory", event.attributes().get("failureCategory"));
        return Map.copyOf(tags);
    }

    private static void put(Map<String, String> tags, String key, String value) {
        if (value != null && !value.isBlank()) tags.put(key, value);
    }
}
