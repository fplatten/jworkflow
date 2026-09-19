package org.jworkflow.persistence;

import org.jworkflow.model.*;

import java.util.List;
import java.util.Optional;

/**
 * Snapshot persistence and bounded query port. Updates use the expected optimistic lock version; a conflict must
 * roll back dependent command effects. Composite active-instance cursors distinguish equal update timestamps.
 */
public interface WorkflowInstanceRepository {
    /**
     * Inserts an initial snapshot. Existing instance identity is a persistence conflict.
     * @param snapshot point-in-time workflow snapshot
     */
    void insert(WorkflowSnapshot snapshot);

    /**
     * Updates only if the expected lock version still matches and returns the newly versioned snapshot. Roll back
     * all dependent effects on conflict.
     * @param snapshot point-in-time workflow snapshot
     * @param expectedLockVersion optimistic version that must still match the stored snapshot
     * @return the instance snapshot
     */
    WorkflowSnapshot update(WorkflowSnapshot snapshot, long expectedLockVersion);

    /**
     * Looks up the stored value by its stable identity without treating absence as an error.
     * @param instanceId workflow instance identity
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowSnapshot> findById(WorkflowInstanceId instanceId);

    /**
     * Looks up a snapshot or detail by correlation identity; use explicit scoped routes when uniqueness matters.
     * @param correlationId identity shared by related commands and events
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowSnapshot> findByCorrelationId(String correlationId);

    /**
     * Looks up the instance only if its stored lifecycle state is active.
     * @param instanceId workflow instance identity
     * @return the matching value, or an empty optional when absent
     */
    default Optional<WorkflowSnapshot> findActiveById(WorkflowInstanceId instanceId) {
        return findById(instanceId).filter(WorkflowInstanceRepository::active);
    }

    /**
     * Finds bounded active candidates under a workflow/correlation scope. The compatibility implementation filters
     * a bounded active page; adapters can provide a dedicated query.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param correlationId identity shared by related commands and events
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowSnapshot> findActiveByCorrelation(
            String workflowKey, String correlationId, int limit) {
        requireLimit(limit);
        return findActive(limit).stream()
                .filter(snapshot -> snapshot.workflowKey().equals(workflowKey))
                .filter(snapshot -> java.util.Objects.equals(snapshot.correlationId(), correlationId))
                .toList();
    }

    /**
     * Finds bounded active route candidates under the supplied workflow/business scope.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowSnapshot> findActiveByBusinessKey(
            String workflowKey, String businessKey, int limit) {
        requireLimit(limit);
        return findActive(limit).stream()
                .filter(snapshot -> snapshot.workflowKey().equals(workflowKey))
                .filter(snapshot -> snapshot.businessKey().equals(businessKey))
                .toList();
    }

    /**
     * Stable keyset page ordered by updatedAt then instanceId. A null cursor requests the first page.
     * @param cursor last composite key from the preceding page; null starts traversal
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowSnapshot> findActiveAfter(ActiveWorkflowCursor cursor, int limit) {
        requireLimit(limit);
        return findActive(limit);
    }

    /**
     * Looks up a workflow by its scoped workflow/business identity.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @return the matching value, or an empty optional when absent
     */
    default Optional<WorkflowSnapshot> findByBusinessKey(String workflowKey, String businessKey) {
        return findActiveByBusinessKey(workflowKey, businessKey, 2).stream().findFirst();
    }

    /**
     * Returns active snapshots in stable update-time/identity order; a composite cursor continues after the
     * preceding page.
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowSnapshot> findActive(int limit);

    /**
     * Read-side pagination ordered deterministically by creation time and identity.
     * @param limit maximum number of rows requested
     * @param offset zero-based number of rows to skip
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowSnapshot> findAll(int limit, int offset) { return findActive(limit); }

    /**
     * Finds snapshots with the requested state. The compatibility implementation filters active snapshots;
     *  adapters must override it to include terminal states.
     * @param status instance lifecycle state
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowSnapshot> findByStatus(org.jworkflow.model.WorkflowStatus status, int limit) {
        return findActive(limit).stream().filter(snapshot -> snapshot.status() == status).toList();
    }

    /**
     * Finds active snapshots last updated at or before the supplied threshold.
     * @param updatedBefore inclusive last-update threshold for stalled instances
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowSnapshot> findStuck(java.time.Instant updatedBefore, int limit) {
        return findActive(limit).stream().filter(snapshot -> !snapshot.updatedAt().isAfter(updatedBefore)).toList();
    }

    /**
     * Compatibility alias for source compatibility. New code should use insert/update explicitly.
     * @param snapshot point-in-time workflow snapshot
     */
    default void save(WorkflowSnapshot snapshot) {
        if (snapshot.lockVersion() == 0) insert(snapshot);
        else update(snapshot, snapshot.lockVersion() - 1);
    }

    /**
     * Compatibility alias for source compatibility.
     * @param instanceId workflow instance identity
     * @return the matching value, or an empty optional when absent
     */
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
