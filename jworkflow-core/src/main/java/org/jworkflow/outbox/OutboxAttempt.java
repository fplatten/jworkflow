package org.jworkflow.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable publication-attempt history, including destination outcome and timing.
 * @param attemptId identity of the immutable attempt record
 * @param messageId durable message identity
 * @param attemptNumber one-based attempt number
 * @param status publication state
 * @param errorCode stable machine-readable failure category
 * @param errorMessage human-readable failure detail
 * @param createdAt creation time
 */
public record OutboxAttempt(
        UUID attemptId,
        UUID messageId,
        int attemptNumber,
        OutboxMessageStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt
) {
    /**
     * Creates this value from the supplied components.
     * @param attemptId identity of the immutable attempt record
     * @param messageId durable message identity
     * @param attemptNumber one-based attempt number
     * @param status publication state
     * @param errorCode stable machine-readable failure category
     * @param errorMessage human-readable failure detail
     * @param createdAt creation time
     * @throws NullPointerException if messageId, status is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public OutboxAttempt {
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        Objects.requireNonNull(messageId, "messageId");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(status, "status");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
