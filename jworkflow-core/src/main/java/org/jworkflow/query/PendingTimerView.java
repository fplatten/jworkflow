package org.jworkflow.query;
import org.jworkflow.model.*;import java.time.Instant;import java.util.UUID;
/**
 * Read-only timer schedule and claim/retry observation.
 * @param timerId durable timer identity
 * @param instanceId workflow instance identity
 * @param stepName workflow node name for this step
 * @param dueAt timer eligibility deadline
 * @param status timer lifecycle state
 * @param attemptCount number of processing attempts already recorded
 * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
 * @param claimedBy worker holding the current lease, or null when unclaimed
 * @param claimUntil lease expiration instant, after the acquisition time
 */
public record PendingTimerView(UUID timerId,WorkflowInstanceId instanceId,String stepName,Instant dueAt,
 WorkflowTimerStatus status,int attemptCount,Instant nextAttemptAt,String claimedBy,Instant claimUntil){}
