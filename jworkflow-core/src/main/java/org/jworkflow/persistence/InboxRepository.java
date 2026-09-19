package org.jworkflow.persistence;

import org.jworkflow.inbox.InboxAttempt;
import org.jworkflow.inbox.InboxInsertResult;
import org.jworkflow.inbox.InboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable acceptance, claim and processing history port. JDBC claims require a short write-capable transaction.
 * Fenced completion validates owner and acquisition token; dependent workflow/attempt/status writes must share
 * that transaction. PostgreSQL rejects legacy owner-only completion. Default fenced methods fail explicitly until
 * custom implementations provide fencing.
 */
public interface InboxRepository {
    /**
     * Inserts by source/external-event identity or returns the stored first-arrival message. Redelivery does not
     *  replace the original body.
     * @param message durable incoming message envelope
     * @return the stored message and whether this call inserted it
     */
    InboxInsertResult insertIfAbsent(InboxMessage message);
    /**
     * Looks up the stored value by its stable identity without treating absence as an error.
     * @param messageId durable message identity
     * @return the matching value, or an empty optional when absent
     */
    Optional<InboxMessage> findById(UUID messageId);
    /**
     * Finds the first accepted inbox message for a source system and external event ID.
     * @param sourceSystem external source identity used in routing, audit or inbox deduplication
     * @param externalEventId stable message identity within its source system
     * @return the matching value, or an empty optional when absent
     */
    Optional<InboxMessage> findByExternalIdentity(String sourceSystem, String externalEventId);
    /**
     * Acquires at most limit eligible items in queue order until the supplied lease deadline. This legacy API does
     * not promise generation fencing; built-in workers use the fenced variant.
     * @param now clock instant used for eligibility or retry calculation
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    List<InboxMessage> claimEligible(Instant now, String ownerId, Instant claimUntil, int limit);
    /**
     * Acquires at most limit eligible items in queue order until the supplied lease deadline. Each acquisition
     * carries a new opaque token, even for the same owner. The compatibility default rejects implementations
     * without fencing.
     * @param now clock instant used for eligibility or retry calculation
     * @param owner worker identity that acquired the lease
     * @param until lease expiration instant, after the acquisition time
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<InboxMessage> claimEligibleFenced(Instant now,String owner,Instant until,int limit){throw new UnsupportedOperationException("Fenced inbox claims are not supported");}
    /**
     * Validate and hold this generation against reclaim until the enclosing transaction ends.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     */
    default void requireClaim(UUID id,String owner,String token){throw new UnsupportedOperationException("Fenced inbox claims are not supported");}
    /**
     * Records successful inbox processing. Requires the matching owner and acquisition token. Expiry alone does
     * not invalidate an unreclaimed token; reclaim or release does. A stale guard must roll back dependent writes
     * in the same transaction. The compatibility default rejects unsupported fencing.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     * @param at time to record for the transition
     */
    default void markProcessed(UUID id,String owner,String token,Instant at){throw new UnsupportedOperationException("Fenced inbox completion is not supported");}
    /**
     * Schedules another message attempt. Requires the matching owner and acquisition token. Expiry alone does not
     * invalidate an unreclaimed token; reclaim or release does. A stale guard must roll back dependent writes in
     * the same transaction. The compatibility default rejects unsupported fencing.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     * @param next deadline for the next eligible attempt
     * @param error failure detail to record or report
     */
    default void scheduleRetry(UUID id,String owner,String token,Instant next,String error){throw new UnsupportedOperationException("Fenced inbox retry is not supported");}
    /**
     * Stops automatic retries and records dead-letter state. Requires the matching owner and acquisition token.
     * Expiry alone does not invalidate an unreclaimed token; reclaim or release does. A stale guard must roll back
     * dependent writes in the same transaction. The compatibility default rejects unsupported fencing.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     * @param error failure detail to record or report
     * @param at time to record for the transition
     */
    default void markDeadLetter(UUID id,String owner,String token,String error,Instant at){throw new UnsupportedOperationException("Fenced inbox dead letter is not supported");}
    /**
     * Records successful inbox processing. Legacy owner-only operation: PostgreSQL rejects it; SQLite retains
     * weaker compatibility semantics. Prefer the token-bearing overload for worker completion.
     * @param messageId durable message identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param processedAt time processing completed
     */
    void markProcessed(UUID messageId, String ownerId, Instant processedAt);
    /**
     * Schedules another message attempt. Legacy owner-only operation: PostgreSQL rejects it; SQLite retains weaker
     * compatibility semantics. Prefer the token-bearing overload for worker completion.
     * @param messageId durable message identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param error failure detail to record or report
     */
    void scheduleRetry(UUID messageId, String ownerId, Instant nextAttemptAt, String error);
    /**
     * Stops automatic retries and records dead-letter state. Legacy owner-only operation: PostgreSQL rejects it;
     * SQLite retains weaker compatibility semantics. Prefer the token-bearing overload for worker completion.
     * @param messageId durable message identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param error failure detail to record or report
     * @param deadLetteredAt time terminal failure was recorded
     */
    void markDeadLetter(UUID messageId, String ownerId, String error, Instant deadLetteredAt);
    /**
     * Releases leases expiring at or before now, invalidating their tokens and making work
     * eligible for retry. PostgreSQL releases at most 1,000 per queue per call.
     * @param now clock instant used for eligibility or retry calculation
     * @return the number of leases released
     */
    int releaseExpiredClaims(Instant now);
    /**
     * Appends an immutable attempt record; include it in the enclosing transaction with the corresponding state
     * transition.
     * @param attempt immutable attempt history entry
     */
    void appendAttempt(InboxAttempt attempt);
    /**
     * Returns append-only attempt history for the requested message, timer or event identity.
     * @param messageId durable message identity
     * @return the matching values in the order defined by this operation
     */
    List<InboxAttempt> findAttempts(UUID messageId);
    /**
     * Schedules manual inbox reprocessing while retaining original identity, payload and attempt history.
     * @param messageId durable message identity
     * @param requestedAt time at which the operation was requested
     */
    void requestReprocessing(UUID messageId, Instant requestedAt);
}
