package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jworkflow.events.EventMessage;

public record WorkflowSignal(
        String eventType,
        String correlationId,
        String causationId,
        String businessKey,
        Instant occurredAt,
        Map<String, String> metadata,
        EventMessage message
) {
    public WorkflowSignal(String eventType, String correlationId, String causationId, String businessKey,
                          Instant occurredAt, Map<String, String> metadata) {
        this(eventType, correlationId, causationId, businessKey, occurredAt, metadata, EventMessage.empty());
    }

    public WorkflowSignal {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        message = message == null ? EventMessage.empty() : message;
    }

    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    public CausationId causationIdentity() { return CausationId.of(causationId); }
}
