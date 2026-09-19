package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jworkflow.events.EventMessage;

/**
 * Named input for one workflow instance, with business/correlation identities, occurrence time, metadata and the
 *  original message envelope.
 * @param eventType lifecycle observation kind
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param businessKey application business identity associated with the workflow
 * @param occurredAt event occurrence time
 * @param metadata signal metadata
 * @param message payload envelope and its media/schema metadata
 */
public record WorkflowSignal(
        String eventType,
        String correlationId,
        String causationId,
        String businessKey,
        Instant occurredAt,
        Map<String, String> metadata,
        EventMessage message
) {
    /**
     * Creates this value from the supplied components.
     * @param eventType lifecycle observation kind
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param businessKey application business identity associated with the workflow
     * @param occurredAt event occurrence time
     * @param metadata signal metadata
     */
    public WorkflowSignal(String eventType, String correlationId, String causationId, String businessKey,
                          Instant occurredAt, Map<String, String> metadata) {
        this(eventType, correlationId, causationId, businessKey, occurredAt, metadata, EventMessage.empty());
    }

    /**
     * Creates this value from the supplied components.
     * @param eventType lifecycle observation kind
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param businessKey application business identity associated with the workflow
     * @param occurredAt event occurrence time
     * @param metadata signal metadata
     * @param message payload envelope and its media/schema metadata
     * @throws NullPointerException if eventType, correlationId, occurredAt is null
     */
    public WorkflowSignal {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        message = message == null ? EventMessage.empty() : message;
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
}
