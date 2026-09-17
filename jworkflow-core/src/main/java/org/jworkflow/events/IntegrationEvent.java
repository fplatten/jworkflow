package org.jworkflow.events;

import java.util.Objects;

/** Infrastructure-facing immutable event envelope. */
public record IntegrationEvent(EventMetadata metadata, EventMessage message) {
    public IntegrationEvent {
        Objects.requireNonNull(metadata, "metadata");
        message = message == null ? EventMessage.empty() : message;
    }

    public static IntegrationEvent named(String eventName, Object payload) {
        return new IntegrationEvent(EventMetadata.named(eventName), EventMessage.json(payload));
    }

    public static IntegrationEvent from(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");
        return new IntegrationEvent(event.metadata(), event.message());
    }

    public EventName eventName() { return metadata.eventName(); }
    public WorkflowEvent toWorkflowEvent() { return new WorkflowEvent(metadata, message); }
}
