package org.jworkflow.persistence;

import org.jworkflow.model.WorkflowInstanceId;

import java.time.Instant;
import java.util.Objects;

/**
 * Composite active-instance keyset position using update time and instance ID to distinguish timestamp ties.
 * @param updatedAt last recorded update time
 * @param instanceId workflow instance identity
 */
public record ActiveWorkflowCursor(Instant updatedAt, WorkflowInstanceId instanceId) {
    /**
     * Creates this value from the supplied components.
     * @param updatedAt last recorded update time
     * @param instanceId workflow instance identity
     * @throws NullPointerException if updatedAt, instanceId is null
     */
    public ActiveWorkflowCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
