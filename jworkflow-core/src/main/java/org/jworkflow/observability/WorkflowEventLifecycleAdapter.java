package org.jworkflow.observability;

import org.jworkflow.events.EventMetadata;
import org.jworkflow.events.EventPublisher;
import org.jworkflow.events.WorkflowEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkflowEventLifecycleAdapter implements EventPublisher {
    private static final Map<String, WorkflowLifecycleEventType> TYPES = Map.ofEntries(
            Map.entry("workflow.started", WorkflowLifecycleEventType.WORKFLOW_STARTED),
            Map.entry("workflow.completed", WorkflowLifecycleEventType.WORKFLOW_COMPLETED),
            Map.entry("workflow.failed", WorkflowLifecycleEventType.WORKFLOW_FAILED),
            Map.entry("step.entered", WorkflowLifecycleEventType.STEP_ENTERED),
            Map.entry("step.completed", WorkflowLifecycleEventType.STEP_COMPLETED),
            Map.entry("step.failed", WorkflowLifecycleEventType.STEP_FAILED),
            Map.entry("transition.taken", WorkflowLifecycleEventType.TRANSITION_TAKEN),
            Map.entry("event.received", WorkflowLifecycleEventType.EVENT_RECEIVED),
            Map.entry("event.correlated", WorkflowLifecycleEventType.EVENT_CORRELATED),
            Map.entry("event.ignored", WorkflowLifecycleEventType.EVENT_IGNORED),
            Map.entry("retry.scheduled", WorkflowLifecycleEventType.RETRY_SCHEDULED),
            Map.entry("timer.fired", WorkflowLifecycleEventType.TIMER_FIRED));

    private final WorkflowLifecycleObserver observer;

    public WorkflowEventLifecycleAdapter(WorkflowLifecycleObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void publish(WorkflowEvent event) {
        WorkflowLifecycleEventType type = TYPES.get(event.eventName().value());
        if (type == null) return;
        EventMetadata metadata = event.metadata();
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>(metadata.headers());
        observer.observe(new WorkflowLifecycleEvent(type, metadata.occurredAt(), metadata.workflowInstanceId(),
                attributes.remove("workflowKey"), attributes.remove("workflowVersion"), attributes.get("state"),
                attributes.get("step"), metadata.correlationId(), metadata.causationId(), metadata.traceId(), attributes));
    }
}
