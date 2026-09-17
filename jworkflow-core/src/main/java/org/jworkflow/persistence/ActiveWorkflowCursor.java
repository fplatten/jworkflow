package org.jworkflow.persistence;

import org.jworkflow.model.WorkflowInstanceId;

import java.time.Instant;
import java.util.Objects;

public record ActiveWorkflowCursor(Instant updatedAt, WorkflowInstanceId instanceId) {
    public ActiveWorkflowCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
