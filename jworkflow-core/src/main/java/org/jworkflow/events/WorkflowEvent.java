package org.jworkflow.events;


import java.util.Objects;

/**
 * Immutable workflow event identity, payload and routing context. The envelope can carry exact instance and
 *  definition identities for durable routing and history.
 * @param metadata event identity, routing and provenance metadata
 * @param message payload envelope and its media/schema metadata
 */
public record WorkflowEvent(
        EventMetadata metadata,
        EventMessage message
) {
    /**
     * Creates this value from the supplied components.
     * @param metadata event identity, routing and provenance metadata
     * @param message payload envelope and its media/schema metadata
     * @throws NullPointerException if metadata is null
     */
    public WorkflowEvent {
        Objects.requireNonNull(metadata, "metadata");
        message = message == null ? EventMessage.empty() : message;
    }

    /**
     * Creates an event with generated metadata and an empty message body.
     * @param eventName event name matched by workflow transitions or subscribers
     * @return the resulting workflow event
     */
    public static WorkflowEvent of(String eventName) {
        return new WorkflowEvent(EventMetadata.named(eventName), EventMessage.empty());
    }

    /**
     * Creates an event with generated metadata and, when supplied, a JSON payload.
     * @param eventName event name matched by workflow transitions or subscribers
     * @return the resulting workflow event
     */
    public static WorkflowEvent named(String eventName) {
        return of(eventName);
    }

    /**
     * Creates an event with generated metadata and, when supplied, a JSON payload.
     * @param eventName event name matched by workflow transitions or subscribers
     * @param payload supported event body; null denotes an absent body
     * @return the resulting workflow event
     */
    public static WorkflowEvent named(String eventName, Object payload) {
        return new WorkflowEvent(EventMetadata.named(eventName), EventMessage.json(payload));
    }

    /**
     * Returns the event name carried by the immutable metadata.
     * @return the event name carried by the immutable metadata
     */
    public EventName eventName() {
        return metadata.eventName();
    }

    /**
     * Copies the event with replacement metadata and the original message.
     * @param metadata event identity, routing and provenance metadata
     * @return the resulting workflow event
     */
    public WorkflowEvent withMetadata(EventMetadata metadata) {
        return new WorkflowEvent(metadata, message);
    }
}
