package org.jworkflow.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable history entry for one leased timer execution attempt.
 * @param attemptId identity of the immutable attempt record
 * @param timerId durable timer identity
 * @param attemptNumber one-based attempt number
 * @param status timer lifecycle state
 * @param ownerId worker identity; not a substitute for an acquisition token
 * @param errorMessage human-readable failure detail
 * @param createdAt creation time
 */
public record WorkflowTimerAttempt(UUID attemptId, UUID timerId, int attemptNumber,
                                   WorkflowTimerStatus status, String ownerId,
                                   String errorMessage, Instant createdAt) {
    /**
     * Creates this value from the supplied components.
     * @param attemptId identity of the immutable attempt record
     * @param timerId durable timer identity
     * @param attemptNumber one-based attempt number
     * @param status timer lifecycle state
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param errorMessage human-readable failure detail
     * @param createdAt creation time
     * @throws NullPointerException if timerId, status is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowTimerAttempt {
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        Objects.requireNonNull(timerId, "timerId");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(status, "status");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
