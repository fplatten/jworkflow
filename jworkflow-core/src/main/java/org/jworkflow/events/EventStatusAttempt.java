package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventStatusAttempt(
        UUID statusId,
        UUID eventId,
        UUID attemptId,
        int attemptNumber,
        String idempotencyKey,
        WorkflowInstanceId workflowInstanceId,
        String correlationId,
        EventStatusScope scope,
        String handlerId,
        String destination,
        EventStatusValue status,
        int retryCount,
        Instant nextRetryAt,
        boolean retryEligible,
        boolean terminal,
        String lastErrorCode,
        String lastErrorMessage,
        Instant createdAt
) {
    public EventStatusAttempt {
        statusId = statusId == null ? UUID.randomUUID() : statusId;
        Objects.requireNonNull(eventId, "eventId");
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        Objects.requireNonNull(scope, "scope");
        handlerId = handlerId == null ? "" : handlerId;
        destination = destination == null ? "" : destination;
        Objects.requireNonNull(status, "status");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static EventStatusAttempt listenerFailure(
            WorkflowEvent event,
            String handlerId,
            int attemptNumber,
            Throwable error
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(error, "error");
        return new EventStatusAttempt(
                null,
                event.metadata().eventId(),
                null,
                attemptNumber,
                null,
                event.metadata().workflowInstanceId(),
                event.metadata().correlationId(),
                EventStatusScope.LISTENER,
                handlerId,
                "",
                EventStatusValue.FAILED,
                attemptNumber - 1,
                null,
                true,
                false,
                error.getClass().getName(),
                error.getMessage(),
                null);
    }
}
