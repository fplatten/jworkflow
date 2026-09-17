package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.time.Instant;
import java.util.Objects;

public record EventDeliveryFailure(
        String topic,
        WorkflowEvent event,
        String subscriberId,
        Throwable error,
        Instant failedAt
) {
    public EventDeliveryFailure {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(error, "error");
        failedAt = failedAt == null ? Instant.now() : failedAt;
    }
}
