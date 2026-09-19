package org.jworkflow.events;

import java.util.Objects;

/**
 * Infrastructure-facing immutable event envelope.
 * @param metadata event identity, routing and provenance metadata
 * @param message payload envelope and its media/schema metadata
 */
public record IntegrationEvent(EventMetadata metadata, EventMessage message) {
    /**
     * Creates this value from the supplied components.
     * @param metadata event identity, routing and provenance metadata
     * @param message payload envelope and its media/schema metadata
     * @throws NullPointerException if metadata is null
     */
    public IntegrationEvent {
        Objects.requireNonNull(metadata, "metadata");
        message = message == null ? EventMessage.empty() : message;
    }

    /**
     * Creates an integration event with generated metadata and a JSON message envelope.
     * @param eventName event name matched by workflow transitions or subscribers
     * @param payload supported event body; null denotes an absent body
     * @return the resulting integration event
     */
    public static IntegrationEvent named(String eventName, Object payload) {
        return new IntegrationEvent(EventMetadata.named(eventName), EventMessage.json(payload));
    }

    /**
     * Adapts a workflow event without dropping its metadata or message envelope.
     * @param event event to deliver or inspect
     * @return the resulting integration event
     * @throws NullPointerException if event is null
     */
    public static IntegrationEvent from(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");
        return new IntegrationEvent(event.metadata(), event.message());
    }

    /**
     * Returns the event name carried by the immutable metadata.
     * @return the event name carried by the immutable metadata
     */
    public EventName eventName() { return metadata.eventName(); }
    /**
     * Converts this infrastructure envelope into the workflow event representation.
     * @return the resulting workflow event
     */
    public WorkflowEvent toWorkflowEvent() { return new WorkflowEvent(metadata, message); }
}
