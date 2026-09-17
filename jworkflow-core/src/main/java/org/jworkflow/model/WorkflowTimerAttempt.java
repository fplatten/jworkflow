package org.jworkflow.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable history entry for one leased timer execution attempt. */
public record WorkflowTimerAttempt(UUID attemptId, UUID timerId, int attemptNumber,
                                   WorkflowTimerStatus status, String ownerId,
                                   String errorMessage, Instant createdAt) {
    public WorkflowTimerAttempt {
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        Objects.requireNonNull(timerId, "timerId");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(status, "status");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
