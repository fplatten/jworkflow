package org.jworkflow.outbox;

import org.jworkflow.events.EventMessage;
import org.jworkflow.events.CorrelationId;
import org.jworkflow.events.CausationId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable publication envelope with retry and lease state. Destination plus idempotency key identifies duplicate
 *  enqueue requests. Acquisition tokens distinguish repeated claims even by the same worker; publication remains
 * at
 *  least once.
 * @param messageId durable message identity
 * @param eventId event identity associated with the message or history row
 * @param destination registered publication destination
 * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
 *      outcome
 * @param message payload envelope and its media/schema metadata
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param createdAt creation time
 * @param publishedAt time successful publication was recorded
 * @param status publication state
 * @param attemptCount number of processing attempts already recorded
 * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
 * @param lastError most recently recorded failure detail
 * @param claimedBy worker holding the current lease, or null when unclaimed
 * @param claimUntil lease expiration instant, after the acquisition time
 * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
 */
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
        Instant claimUntil,
        String claimToken
) {
    /**
     * Creates this value from the supplied components.
     * @param messageId durable message identity
     * @param eventId event identity associated with the message or history row
     * @param destination registered publication destination
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *      outcome
     * @param message payload envelope and its media/schema metadata
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param createdAt creation time
     * @param publishedAt time successful publication was recorded
     * @param status publication state
     * @param attemptCount number of processing attempts already recorded
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param lastError most recently recorded failure detail
     * @param claimedBy worker holding the current lease, or null when unclaimed
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
     * @throws NullPointerException if eventId is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public OutboxMessage {
        if (claimToken != null && (claimToken.isBlank() || claimedBy == null)) throw new IllegalArgumentException("claimToken requires an owner");
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

    /**
     * Compatibility constructor; legacy records carry no generation token.
     * @param messageId durable message identity
     * @param eventId event identity associated with the message or history row
     * @param destination registered publication destination
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *      outcome
     * @param message payload envelope and its media/schema metadata
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param createdAt creation time
     * @param publishedAt time successful publication was recorded
     * @param status publication state
     * @param attemptCount number of processing attempts already recorded
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param lastError most recently recorded failure detail
     * @param claimedBy worker holding the current lease, or null when unclaimed
     * @param claimUntil lease expiration instant, after the acquisition time
     */
    public OutboxMessage(UUID messageId,UUID eventId,String destination,String idempotencyKey,EventMessage message,
                         String correlationId,String causationId,Instant createdAt,Instant publishedAt,OutboxMessageStatus status,
                         int attemptCount,Instant nextAttemptAt,String lastError,String claimedBy,Instant claimUntil) {
        this(messageId,eventId,destination,idempotencyKey,message,correlationId,causationId,createdAt,publishedAt,status,attemptCount,nextAttemptAt,lastError,claimedBy,claimUntil,null);
    }

    /**
     * Returns the correlation string as a typed identity, or null when absent.
     * @return the correlation string as a typed identity, or null when absent
     */
    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    /**
     * Returns the causation string as a typed identity, or null when absent.
     * @return the causation string as a typed identity, or null when absent
     */
    public CausationId causationIdentity() { return CausationId.of(causationId); }
}
