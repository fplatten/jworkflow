package org.jworkflow.inbox;

import org.jworkflow.events.EventMessage;
import org.jworkflow.events.CorrelationId;
import org.jworkflow.events.CausationId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InboxMessage(
        UUID messageId,
        String externalEventId,
        String sourceSystem,
        EventMessage message,
        String correlationId,
        String causationId,
        Instant receivedAt,
        Instant processedAt,
        InboxMessageStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        String claimedBy,
        Instant claimUntil
) {
    public InboxMessage {
        messageId = messageId == null ? UUID.randomUUID() : messageId;
        requireText(externalEventId, "externalEventId");
        requireText(sourceSystem, "sourceSystem");
        message = message == null ? EventMessage.empty() : message;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        status = status == null ? InboxMessageStatus.RECEIVED : status;
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
        if ((claimedBy == null) != (claimUntil == null)) {
            throw new IllegalArgumentException("claimedBy and claimUntil must be set together");
        }
    }

    public String deduplicationKey() { return sourceSystem + "\u0000" + externalEventId; }
    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    public CausationId causationIdentity() { return CausationId.of(causationId); }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
