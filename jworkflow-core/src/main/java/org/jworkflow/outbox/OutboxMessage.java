package org.jworkflow.outbox;

import org.jworkflow.events.EventMessage;
import org.jworkflow.events.CorrelationId;
import org.jworkflow.events.CausationId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxMessage(
        UUID messageId,
        UUID eventId,
        String destination,
        String idempotencyKey,
        EventMessage message,
        String correlationId,
        String causationId,
        Instant createdAt,
        Instant publishedAt,
        OutboxMessageStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        String claimedBy,
        Instant claimUntil
) {
    public OutboxMessage {
        messageId = messageId == null ? UUID.randomUUID() : messageId;
        Objects.requireNonNull(eventId, "eventId");
        if (destination == null || destination.isBlank()) throw new IllegalArgumentException("destination is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        message = message == null ? EventMessage.empty() : message;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        status = status == null ? OutboxMessageStatus.PENDING : status;
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
        if ((claimedBy == null) != (claimUntil == null)) {
            throw new IllegalArgumentException("claimedBy and claimUntil must be set together");
        }
    }

    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    public CausationId causationIdentity() { return CausationId.of(causationId); }
}
