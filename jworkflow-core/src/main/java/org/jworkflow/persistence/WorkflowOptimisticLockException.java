package org.jworkflow.persistence;

import org.jworkflow.model.WorkflowInstanceId;

import java.util.Objects;

/**
 * A snapshot update lost an optimistic lock-version race. Roll back the entire dependent command unit before
 * reconciling or retrying.
 */
public final class WorkflowOptimisticLockException extends WorkflowPersistenceException {
    private final transient WorkflowInstanceId instanceId;
    /** Lock version the caller expected to replace. */
    private final long expectedVersion;
    /** Conflicting stored version, or null when unavailable. */
    private final Long observedVersion;

    /**
     * Creates a workflow optimistic lock exception with the supplied diagnostic context.
     * @param instanceId workflow instance identity
     * @param expectedVersion optimistic version required for the update
     */
    public WorkflowOptimisticLockException(WorkflowInstanceId instanceId, long expectedVersion) {
        this(instanceId, expectedVersion, null);
    }

    /**
     * Creates a workflow optimistic lock exception with the supplied diagnostic context.
     * @param instanceId workflow instance identity
     * @param expectedVersion optimistic version required for the update
     * @param observedVersion optimistic version found in persistence
     * @throws NullPointerException if instanceId is null
     */
    public WorkflowOptimisticLockException(WorkflowInstanceId instanceId, long expectedVersion, Long observedVersion) {
        super("Stale workflow instance " + instanceId + ": expected lock version " + expectedVersion
                + (observedVersion == null ? "" : ", observed " + observedVersion));
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.expectedVersion = expectedVersion;
        this.observedVersion = observedVersion;
    }

    /**
     * Returns workflow instance identity.
     * @return workflow instance identity
     */
    public WorkflowInstanceId instanceId() { return instanceId; }
    /**
     * Returns optimistic version required for the update.
     * @return optimistic version required for the update
     */
    public long expectedVersion() { return expectedVersion; }
    /**
     * Returns optimistic version found in persistence.
     * @return optimistic version found in persistence
     */
    public Long observedVersion() { return observedVersion; }
}
