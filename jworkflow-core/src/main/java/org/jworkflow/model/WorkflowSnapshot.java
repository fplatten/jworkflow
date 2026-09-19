package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Persisted instance state pinned to an exact workflow definition revision. Required identities, state and
 *  timestamps are validated; the optimistic lock version is nonnegative and variables are defensively copied. A
 *  snapshot is a point-in-time value, not a live view. PostgreSQL relational Instants use exact numeric epoch
 *  seconds; JSON-embedded times remain strings.
 * @param instanceId workflow instance identity
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion nonblank workflow definition version
 * @param workflowRevision exact immutable definition revision pinned by the workflow
 * @param businessKey application business identity associated with the workflow
 * @param correlationId identity shared by related commands and events
 * @param state current workflow node name
 * @param status instance lifecycle state
 * @param variables workflow variable values; durable values must follow the supported JSON value model
 * @param lockVersion nonnegative optimistic snapshot version
 * @param createdAt creation time
 * @param updatedAt last recorded update time
 */
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
    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion nonblank workflow definition version
     * @param workflowRevision exact immutable definition revision pinned by the workflow
     * @param businessKey application business identity associated with the workflow
     * @param correlationId identity shared by related commands and events
     * @param state current workflow node name
     * @param status instance lifecycle state
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @param lockVersion nonnegative optimistic snapshot version
     * @param createdAt creation time
     * @param updatedAt last recorded update time
     * @throws NullPointerException if instanceId, workflowKey, workflowVersion, businessKey, state, status,
     *      createdAt, updatedAt is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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

    /**
     * Copies the snapshot with a replacement nonnegative lock version without modifying persistence.
     * @param newLockVersion replacement nonnegative optimistic version
     * @return the instance snapshot
     */
    public WorkflowSnapshot withLockVersion(long newLockVersion) {
        return new WorkflowSnapshot(instanceId, workflowKey, workflowVersion, workflowRevision, businessKey,
                correlationId, state, status, variables, newLockVersion, createdAt, updatedAt);
    }

    /**
     * Returns the correlation string as a typed identity, or null when absent.
     * @return the correlation string as a typed identity, or null when absent
     */
    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
}
