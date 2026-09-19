package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable timer occurrence with retry state and optional acquisition token. Each claim generation has a distinct
 *  token; legacy constructors leave it null. Expiry permits recovery but an unreclaimed token may still finish.
 *  Token validation belongs to the repository transaction, not this value object.
 * @param timerId durable timer identity
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param stepName workflow node name for this step
 * @param dueAt timer eligibility deadline
 * @param targetNode node to enter after the transition
 * @param emittedEvent event emitted by the transition when configured
 * @param status timer lifecycle state
 * @param attemptCount number of processing attempts already recorded
 * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
 * @param claimedBy worker holding the current lease, or null when unclaimed
 * @param claimUntil lease expiration instant, after the acquisition time
 * @param createdAt creation time
 * @param updatedAt last recorded update time
 * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
 */
public record WorkflowTimer(
        UUID timerId,
        WorkflowInstanceId workflowInstanceId,
        String stepName,
        Instant dueAt,
        String targetNode,
        EventName emittedEvent,
        WorkflowTimerStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String claimedBy,
        Instant claimUntil,
        Instant createdAt,
        Instant updatedAt,
        String claimToken
) {
    /**
     * Creates this value from the supplied components.
     * @param timerId durable timer identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param stepName workflow node name for this step
     * @param dueAt timer eligibility deadline
     * @param targetNode node to enter after the transition
     * @param emittedEvent event emitted by the transition when configured
     * @param status timer lifecycle state
     * @param attemptCount number of processing attempts already recorded
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param claimedBy worker holding the current lease, or null when unclaimed
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param createdAt creation time
     * @param updatedAt last recorded update time
     * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
     * @throws NullPointerException if workflowInstanceId, stepName, dueAt is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowTimer {
        if (claimToken != null && (claimToken.isBlank() || claimedBy == null)) throw new IllegalArgumentException("claimToken requires an owner");
        timerId = timerId == null ? UUID.randomUUID() : timerId;
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(dueAt, "dueAt");
        status = status == null ? WorkflowTimerStatus.PENDING : status;
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
        if ((claimedBy == null) != (claimUntil == null)) {
            throw new IllegalArgumentException("claimedBy and claimUntil must be set together");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * Compatibility constructor for records created before lease-generation fencing.
     * @param timerId durable timer identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param stepName workflow node name for this step
     * @param dueAt timer eligibility deadline
     * @param targetNode node to enter after the transition
     * @param emittedEvent event emitted by the transition when configured
     * @param status timer lifecycle state
     * @param attemptCount number of processing attempts already recorded
     * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
     * @param claimedBy worker holding the current lease, or null when unclaimed
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param createdAt creation time
     * @param updatedAt last recorded update time
     */
    public WorkflowTimer(UUID timerId, WorkflowInstanceId workflowInstanceId, String stepName, Instant dueAt,
                         String targetNode, EventName emittedEvent, WorkflowTimerStatus status, int attemptCount,
                         Instant nextAttemptAt, String claimedBy, Instant claimUntil, Instant createdAt, Instant updatedAt) {
        this(timerId,workflowInstanceId,stepName,dueAt,targetNode,emittedEvent,status,attemptCount,nextAttemptAt,claimedBy,claimUntil,createdAt,updatedAt,null);
    }

    /**
     * Creates a legacy value without an acquisition token. This constructor does not provide lease-generation
     * fencing.
     * @param timerId durable timer identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param stepName workflow node name for this step
     * @param dueAt timer eligibility deadline
     * @param targetNode node to enter after the transition
     * @param emittedEvent event emitted by the transition when configured
     * @param status legacy timer status name
     */
    public WorkflowTimer(UUID timerId, WorkflowInstanceId workflowInstanceId, String stepName,
                         Instant dueAt, String targetNode, EventName emittedEvent, String status) {
        this(timerId, workflowInstanceId, stepName, dueAt, targetNode, emittedEvent,
                status == null || status.isBlank() ? WorkflowTimerStatus.PENDING : WorkflowTimerStatus.valueOf(status),
                0, dueAt, null, null, Instant.now(), Instant.now());
    }

    /**
     * Returns a timer value marked FIRED; this value transformation does not persist or validate a lease.
     * @return a timer value marked FIRED; this value transformation does not persist or validate a lease
     */
    public WorkflowTimer fired() {
        return new WorkflowTimer(timerId, workflowInstanceId, stepName, dueAt, targetNode, emittedEvent,
                WorkflowTimerStatus.FIRED, attemptCount, nextAttemptAt, null, null, createdAt, Instant.now());
    }

    /**
     * Returns a timer value marked CANCELED; this value transformation does not persist or validate a lease.
     * @return a timer value marked CANCELED; this value transformation does not persist or validate a lease
     */
    public WorkflowTimer canceled() {
        return new WorkflowTimer(timerId, workflowInstanceId, stepName, dueAt, targetNode, emittedEvent,
                WorkflowTimerStatus.CANCELED, attemptCount, nextAttemptAt, null, null, createdAt, Instant.now());
    }
}
