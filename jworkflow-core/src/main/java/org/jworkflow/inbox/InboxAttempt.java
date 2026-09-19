package org.jworkflow.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable history entry for one attempt to translate and process an inbox message.
 * @param attemptId identity of the immutable attempt record
 * @param messageId durable message identity
 * @param attemptNumber one-based attempt number
 * @param status inbox processing state
 * @param errorCode stable machine-readable failure category
 * @param errorMessage human-readable failure detail
 * @param createdAt creation time
 */
public record InboxAttempt(
        UUID attemptId,
        UUID messageId,
        int attemptNumber,
        InboxMessageStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt
) {
    /**
     * Creates this value from the supplied components.
     * @param attemptId identity of the immutable attempt record
     * @param messageId durable message identity
     * @param attemptNumber one-based attempt number
     * @param status inbox processing state
     * @param errorCode stable machine-readable failure category
     * @param errorMessage human-readable failure detail
     * @param createdAt creation time
     * @throws NullPointerException if messageId, status is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public InboxAttempt {
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        Objects.requireNonNull(messageId, "messageId");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(status, "status");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
