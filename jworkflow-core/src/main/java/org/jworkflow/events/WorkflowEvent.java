package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Objects;

public record WorkflowEvent(
        EventMetadata metadata,
        EventMessage message
) {
    public WorkflowEvent {
        Objects.requireNonNull(metadata, "metadata");
        message = message == null ? EventMessage.empty() : message;
    }

    public static WorkflowEvent of(String eventName) {
        return new WorkflowEvent(EventMetadata.named(eventName), EventMessage.empty());
    }

    public static WorkflowEvent named(String eventName) {
        return of(eventName);
    }

    public static WorkflowEvent named(String eventName, Object payload) {
        return new WorkflowEvent(EventMetadata.named(eventName), EventMessage.json(payload));
    }

    public EventName eventName() {
        return metadata.eventName();
    }

    public WorkflowEvent withMetadata(EventMetadata metadata) {
        return new WorkflowEvent(metadata, message);
    }
}
