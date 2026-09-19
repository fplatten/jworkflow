package org.jworkflow.observability;

import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.events.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable lifecycle observation carrying workflow identity, outcome, timing and diagnostic context.
 * @param type lifecycle boundary being observed
 * @param occurredAt event occurrence time
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion workflow definition version
 * @param state current workflow node name
 * @param step workflow step identity
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param traceId host-provided distributed tracing identity
 * @param attributes message attributes subject to the capture policy
 */
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
    /**
     * Creates this value from the supplied components.
     * @param type lifecycle boundary being observed
     * @param occurredAt event occurrence time
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion workflow definition version
     * @param state current workflow node name
     * @param step workflow step identity
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param traceId host-provided distributed tracing identity
     * @param attributes message attributes subject to the capture policy
     * @throws NullPointerException if type is null
     */
    public WorkflowLifecycleEvent {
        Objects.requireNonNull(type, "type");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    /**
     * Returns the correlation string as a typed identity, or null when absent.
     * @return the correlation string as a typed identity, or null when absent
     */
    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    /**
     * Returns the causation string as a typed identity, or null when absent.
     * @return the causation string as a typed identity, or null when absent
     */
    public CausationId causationIdentity() { return CausationId.of(causationId); }
    /**
     * Returns the trace string as a typed identity, or null when absent.
     * @return the trace string as a typed identity, or null when absent
     */
    public TraceId traceIdentity() { return TraceId.of(traceId); }
}
