package org.jworkflow.query;
import org.jworkflow.model.*;import java.time.Instant;import java.util.UUID;
public record PendingTimerView(UUID timerId,WorkflowInstanceId instanceId,String stepName,Instant dueAt,
 WorkflowTimerStatus status,int attemptCount,Instant nextAttemptAt,String claimedBy,Instant claimUntil){
 public static PendingTimerView from(WorkflowTimer t){return new PendingTimerView(t.timerId(),t.workflowInstanceId(),t.stepName(),t.dueAt(),t.status(),t.attemptCount(),t.nextAttemptAt(),t.claimedBy(),t.claimUntil());}}
