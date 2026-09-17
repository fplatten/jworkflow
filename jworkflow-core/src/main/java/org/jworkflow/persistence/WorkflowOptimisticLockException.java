package org.jworkflow.persistence;

import org.jworkflow.model.WorkflowInstanceId;

import java.util.Objects;

public final class WorkflowOptimisticLockException extends WorkflowPersistenceException {
    private final WorkflowInstanceId instanceId;
    private final long expectedVersion;
    private final Long observedVersion;

    public WorkflowOptimisticLockException(WorkflowInstanceId instanceId, long expectedVersion) {
        this(instanceId, expectedVersion, null);
    }

    public WorkflowOptimisticLockException(WorkflowInstanceId instanceId, long expectedVersion, Long observedVersion) {
        super("Stale workflow instance " + instanceId + ": expected lock version " + expectedVersion
                + (observedVersion == null ? "" : ", observed " + observedVersion));
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.expectedVersion = expectedVersion;
        this.observedVersion = observedVersion;
    }

    public WorkflowInstanceId instanceId() { return instanceId; }
    public long expectedVersion() { return expectedVersion; }
    public Long observedVersion() { return observedVersion; }
}
