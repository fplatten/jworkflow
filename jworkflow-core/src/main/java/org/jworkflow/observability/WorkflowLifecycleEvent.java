package org.jworkflow.observability;

import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.events.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record WorkflowLifecycleEvent(
        WorkflowLifecycleEventType type,
        Instant occurredAt,
        WorkflowInstanceId workflowInstanceId,
        String workflowKey,
        String workflowVersion,
        String state,
        String step,
        String correlationId,
        String causationId,
        String traceId,
        Map<String, String> attributes
) {
    public WorkflowLifecycleEvent {
        Objects.requireNonNull(type, "type");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    public CausationId causationIdentity() { return CausationId.of(causationId); }
    public TraceId traceIdentity() { return TraceId.of(traceId); }
}
