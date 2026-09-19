package org.jworkflow.inbox;

import org.jworkflow.events.EventMessage;
import org.jworkflow.events.CorrelationId;
import org.jworkflow.events.CausationId;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable inbound envelope, processing state and lease metadata. Each fenced acquisition has a new token; worker
 *  name alone does not identify a generation. Legacy construction leaves the token null. Changing record
 * components
 *  affects record-derived equality/serialization despite retained constructor overloads.
 * @param messageId durable message identity
 * @param externalEventId stable message identity within its source system
 * @param sourceSystem external source identity used in routing, audit or inbox deduplication
 * @param message payload envelope and its media/schema metadata
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param receivedAt time the inbound message was received
 * @param processedAt time processing completed
 * @param status inbox processing state
 * @param attemptCount number of processing attempts already recorded
 * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
 * @param lastError most recently recorded failure detail
 * @param claimedBy worker holding the current lease, or null when unclaimed
 * @param claimUntil lease expiration instant, after the acquisition time
 * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
 */
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
        Instant claimUntil,
        String claimToken
) {
    /**
     * Creates this value from the supplied components.
     * @param messageId durable message identity
     * @param externalEventId stable message identity within its source system
     * @param sourceSystem external source identity used in routing, audit or inbox deduplication
     * @param message payload envelope and its media/schema metadata
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param receivedAt time the inbound message was received
     * @param processedAt time processing completed
     * @param status inbox processing state
     * @param attemptCount number of processing attempts already recorded
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param lastError most recently recorded failure detail
     * @param claimedBy worker holding the current lease, or null when unclaimed
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public InboxMessage {
        if (claimToken != null && (claimToken.isBlank() || claimedBy == null)) throw new IllegalArgumentException("claimToken requires an owner");
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

    /**
     * Compatibility constructor; legacy records carry no generation token.
     * @param messageId durable message identity
     * @param externalEventId stable message identity within its source system
     * @param sourceSystem external source identity used in routing, audit or inbox deduplication
     * @param message payload envelope and its media/schema metadata
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param receivedAt time the inbound message was received
     * @param processedAt time processing completed
     * @param status inbox processing state
     * @param attemptCount number of processing attempts already recorded
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param lastError most recently recorded failure detail
     * @param claimedBy worker holding the current lease, or null when unclaimed
     * @param claimUntil lease expiration instant, after the acquisition time
     */
    public InboxMessage(UUID messageId,String externalEventId,String sourceSystem,EventMessage message,String correlationId,
                        String causationId,Instant receivedAt,Instant processedAt,InboxMessageStatus status,int attemptCount,
                        Instant nextAttemptAt,String lastError,String claimedBy,Instant claimUntil) {
        this(messageId,externalEventId,sourceSystem,message,correlationId,causationId,receivedAt,processedAt,status,attemptCount,nextAttemptAt,lastError,claimedBy,claimUntil,null);
    }

    /**
     * Combines source system and external event ID with a NUL separator for collision-free command identity;
     * PostgreSQL relational storage escapes it reversibly.
     * @return the resulting text
     */
    public String deduplicationKey() { return sourceSystem + "\u0000" + externalEventId; }
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

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
