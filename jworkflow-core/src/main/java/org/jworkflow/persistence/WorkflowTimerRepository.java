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

    void markFired(java.util.UUID timerId, String ownerId, Instant firedAt);

    void markFailed(java.util.UUID timerId, String ownerId, String error, Instant nextAttemptAt);

    void cancel(java.util.UUID timerId, Instant canceledAt);

    int releaseExpiredClaims(Instant now);

    void appendAttempt(org.jworkflow.model.WorkflowTimerAttempt attempt);

    List<org.jworkflow.model.WorkflowTimerAttempt> findAttempts(java.util.UUID timerId);
}
