package org.jworkflow.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InboxAttempt(
        UUID attemptId,
        UUID messageId,
        int attemptNumber,
        InboxMessageStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt
) {
    public InboxAttempt {
        attemptId = attemptId == null ? UUID.randomUUID() : attemptId;
        Objects.requireNonNull(messageId, "messageId");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(status, "status");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
