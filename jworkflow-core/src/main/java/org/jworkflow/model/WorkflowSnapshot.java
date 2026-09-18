package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record WorkflowSnapshot(
        WorkflowInstanceId instanceId,
        String workflowKey,
        String workflowVersion,
        String workflowRevision,
        String businessKey,
        String correlationId,
        String state,
        WorkflowStatus status,
        Map<String, Object> variables,
        long lockVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowSnapshot {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(workflowVersion, "workflowVersion");
        if (workflowRevision == null || workflowRevision.isBlank()) {
            throw new IllegalArgumentException("workflowRevision is required");
        }
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (lockVersion < 0) {
            throw new IllegalArgumentException("lockVersion must not be negative");
        }
        variables = ImmutableData.copyStringObjectMap(variables);
    }

    public WorkflowSnapshot withLockVersion(long newLockVersion) {
        return new WorkflowSnapshot(instanceId, workflowKey, workflowVersion, workflowRevision, businessKey,
                correlationId, state, status, variables, newLockVersion, createdAt, updatedAt);
    }

    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
}
