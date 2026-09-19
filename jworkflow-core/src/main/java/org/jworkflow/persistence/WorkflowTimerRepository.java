package org.jworkflow.persistence;

import org.jworkflow.model.*;

import java.time.Instant;
import java.util.List;
import org.jworkflow.model.WorkflowInstanceId;

public interface WorkflowTimerRepository {
    void save(WorkflowTimer timer);

    List<WorkflowTimer> dueTimers(Instant now);

    default List<WorkflowTimer> findByWorkflowInstance(WorkflowInstanceId instanceId) { return List.of(); }

    default List<WorkflowTimer> findPending(int limit) { return List.of(); }

    List<WorkflowTimer> claimDue(Instant now, String ownerId, Instant claimUntil, int limit);

    default List<WorkflowTimer> claimDueFenced(Instant now,String ownerId,Instant claimUntil,int limit){throw new UnsupportedOperationException("Fenced timer claims are not supported");}
    /** Validate and hold this generation against reclaim until the enclosing transaction ends. */
    default void requireClaim(java.util.UUID id,String owner,String token){throw new UnsupportedOperationException("Fenced timer claims are not supported");}
    default void markFired(java.util.UUID id,String owner,String token,Instant at){throw new UnsupportedOperationException("Fenced timer completion is not supported");}
    default void markFailed(java.util.UUID id,String owner,String token,String error,Instant next){throw new UnsupportedOperationException("Fenced timer retry is not supported");}
    default void markDeadLetter(java.util.UUID id,String owner,String token,String error,Instant at){throw new UnsupportedOperationException("Fenced timer dead letter is not supported");}

    void markFired(java.util.UUID timerId, String ownerId, Instant firedAt);

    void markFailed(java.util.UUID timerId, String ownerId, String error, Instant nextAttemptAt);

    void cancel(java.util.UUID timerId, Instant canceledAt);

    int releaseExpiredClaims(Instant now);

    void appendAttempt(org.jworkflow.model.WorkflowTimerAttempt attempt);

    List<org.jworkflow.model.WorkflowTimerAttempt> findAttempts(java.util.UUID timerId);
}
