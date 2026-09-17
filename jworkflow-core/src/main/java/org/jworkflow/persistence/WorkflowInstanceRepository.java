package org.jworkflow.persistence;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.List;
import java.util.Optional;

public interface WorkflowInstanceRepository {
    void insert(WorkflowSnapshot snapshot);

    WorkflowSnapshot update(WorkflowSnapshot snapshot, long expectedLockVersion);

    Optional<WorkflowSnapshot> findById(WorkflowInstanceId instanceId);

    Optional<WorkflowSnapshot> findByCorrelationId(String correlationId);

    default Optional<WorkflowSnapshot> findActiveById(WorkflowInstanceId instanceId) {
        return findById(instanceId).filter(WorkflowInstanceRepository::active);
    }

    default List<WorkflowSnapshot> findActiveByCorrelation(
            String workflowKey, String correlationId, int limit) {
        requireLimit(limit);
        return findActive(limit).stream()
                .filter(snapshot -> snapshot.workflowKey().equals(workflowKey))
                .filter(snapshot -> java.util.Objects.equals(snapshot.correlationId(), correlationId))
                .toList();
    }

    default List<WorkflowSnapshot> findActiveByBusinessKey(
            String workflowKey, String businessKey, int limit) {
        requireLimit(limit);
        return findActive(limit).stream()
                .filter(snapshot -> snapshot.workflowKey().equals(workflowKey))
                .filter(snapshot -> snapshot.businessKey().equals(businessKey))
                .toList();
    }

    /** Stable keyset page ordered by updatedAt then instanceId. A null cursor requests the first page. */
    default List<WorkflowSnapshot> findActiveAfter(ActiveWorkflowCursor cursor, int limit) {
        requireLimit(limit);
        return findActive(limit);
    }

    default Optional<WorkflowSnapshot> findByBusinessKey(String workflowKey, String businessKey) {
        return findActiveByBusinessKey(workflowKey, businessKey, 2).stream().findFirst();
    }

    List<WorkflowSnapshot> findActive(int limit);

    /** Read-side pagination ordered deterministically by creation time and identity. */
    default List<WorkflowSnapshot> findAll(int limit, int offset) { return findActive(limit); }

    default List<WorkflowSnapshot> findByStatus(org.jworkflow.model.WorkflowStatus status, int limit) {
        return findActive(limit).stream().filter(snapshot -> snapshot.status() == status).toList();
    }

    default List<WorkflowSnapshot> findStuck(java.time.Instant updatedBefore, int limit) {
        return findActive(limit).stream().filter(snapshot -> !snapshot.updatedAt().isAfter(updatedBefore)).toList();
    }

    /** Transitional alias for source compatibility. New code should use insert/update explicitly. */
    @Deprecated
    default void save(WorkflowSnapshot snapshot) {
        if (snapshot.lockVersion() == 0) insert(snapshot);
        else update(snapshot, snapshot.lockVersion() - 1);
    }

    /** Transitional alias for source compatibility. */
    @Deprecated
    default Optional<WorkflowSnapshot> find(WorkflowInstanceId instanceId) { return findById(instanceId); }

    private static boolean active(WorkflowSnapshot snapshot) {
        return snapshot.status() == org.jworkflow.model.WorkflowStatus.RUNNING
                || snapshot.status() == org.jworkflow.model.WorkflowStatus.WAITING
                || snapshot.status() == org.jworkflow.model.WorkflowStatus.FAILED;
    }

    private static void requireLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }
}
