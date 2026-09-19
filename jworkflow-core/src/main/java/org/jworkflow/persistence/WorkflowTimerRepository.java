package org.jworkflow.persistence;

import org.jworkflow.model.*;

import java.time.Instant;
import java.util.List;
import org.jworkflow.model.WorkflowInstanceId;

/**
 * Durable timer schedule, bounded lease acquisition and attempt history. Claims require a write-capable
 * transaction. Fenced operations validate acquisition tokens and must guard dependent workflow writes in the same
 * transaction. Expired but unreclaimed tokens may finish; release or reclaim invalidates them. PostgreSQL rejects
 * legacy owner-only finalization.
 */
public interface WorkflowTimerRepository {
    /**
     * Saves a timer schedule using the adapter's upsert policy; an active claimed generation must not be
     * overwritten by a competing reschedule.
     * @param timer durable timer occurrence
     */
    void save(WorkflowTimer timer);

    /**
     * Reads timers due at or before the supplied instant without acquiring claims.
     * @param now clock instant used for eligibility or retry calculation
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowTimer> dueTimers(Instant now);

    /**
     * Returns the values associated with an instance; full-history repository methods are not bounded by a page
     * size.
     * @param instanceId workflow instance identity
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowTimer> findByWorkflowInstance(WorkflowInstanceId instanceId) { return List.of(); }

    /**
     * Returns at most the requested number of pending values without acquiring a lease.
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowTimer> findPending(int limit) { return List.of(); }

    /**
     * Acquires at most limit eligible items in queue order until the supplied lease deadline. This legacy API does
     * not promise generation fencing; built-in workers use the fenced variant.
     * @param now clock instant used for eligibility or retry calculation
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowTimer> claimDue(Instant now, String ownerId, Instant claimUntil, int limit);

    /**
     * Acquires at most limit eligible items in queue order until the supplied lease deadline. Each acquisition
     * carries a new opaque token, even for the same owner. The compatibility default rejects implementations
     * without fencing.
     * @param now clock instant used for eligibility or retry calculation
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowTimer> claimDueFenced(Instant now,String ownerId,Instant claimUntil,int limit){throw new UnsupportedOperationException("Fenced timer claims are not supported");}
    /**
     * Validate and hold this generation against reclaim until the enclosing transaction ends.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     */
    default void requireClaim(java.util.UUID id,String owner,String token){throw new UnsupportedOperationException("Fenced timer claims are not supported");}
    /**
     * Records successful timer execution. Requires the matching owner and acquisition token. Expiry alone does not
     * invalidate an unreclaimed token; reclaim or release does. A stale guard must roll back dependent writes in
     * the same transaction. The compatibility default rejects unsupported fencing.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     * @param at time to record for the transition
     */
    default void markFired(java.util.UUID id,String owner,String token,Instant at){throw new UnsupportedOperationException("Fenced timer completion is not supported");}
    /**
     * Schedules a failed timer for retry. Requires the matching owner and acquisition token. Expiry alone does not
     * invalidate an unreclaimed token; reclaim or release does. A stale guard must roll back dependent writes in
     * the same transaction. The compatibility default rejects unsupported fencing.
     * @param id identity of the value to look up or update
     * @param owner worker identity that acquired the lease
     * @param token opaque token returned by the fenced acquisition
     * @param error failure detail to record or report
     * @param next deadline for the next eligible attempt
     */
    default void markFailed(java.util.UUID id,String owner,String token,String error,Instant next){throw new UnsupportedOperationException("Fenced timer retry is not supported");}
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
    default void markDeadLetter(java.util.UUID id,String owner,String token,String error,Instant at){throw new UnsupportedOperationException("Fenced timer dead letter is not supported");}

    /**
     * Records successful timer execution. Legacy owner-only operation: PostgreSQL rejects it; SQLite retains
     * weaker compatibility semantics. Prefer the token-bearing overload for worker completion.
     * @param timerId durable timer identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param firedAt time timer completion was recorded
     */
    void markFired(java.util.UUID timerId, String ownerId, Instant firedAt);

    /**
     * Schedules a failed timer for retry. Legacy owner-only operation: PostgreSQL rejects it; SQLite retains
     * weaker compatibility semantics. Prefer the token-bearing overload for worker completion.
     * @param timerId durable timer identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param error failure detail to record or report
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     */
    void markFailed(java.util.UUID timerId, String ownerId, String error, Instant nextAttemptAt);

    /**
     * Cancels a timer schedule and invalidates its current claim.
     * @param timerId durable timer identity
     * @param canceledAt time cancellation was requested
     */
    void cancel(java.util.UUID timerId, Instant canceledAt);

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
    void appendAttempt(org.jworkflow.model.WorkflowTimerAttempt attempt);

    /**
     * Returns append-only attempt history for the requested message, timer or event identity.
     * @param timerId durable timer identity
     * @return the matching values in the order defined by this operation
     */
    List<org.jworkflow.model.WorkflowTimerAttempt> findAttempts(java.util.UUID timerId);
}
