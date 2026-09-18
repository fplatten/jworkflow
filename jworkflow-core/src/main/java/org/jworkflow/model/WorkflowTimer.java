package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkflowTimer(
        UUID timerId,
        WorkflowInstanceId workflowInstanceId,
        String stepName,
        Instant dueAt,
        String targetNode,
        EventName emittedEvent,
        WorkflowTimerStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String claimedBy,
        Instant claimUntil,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowTimer {
        timerId = timerId == null ? UUID.randomUUID() : timerId;
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(dueAt, "dueAt");
        status = status == null ? WorkflowTimerStatus.PENDING : status;
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
        if ((claimedBy == null) != (claimUntil == null)) {
            throw new IllegalArgumentException("claimedBy and claimUntil must be set together");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public WorkflowTimer(UUID timerId, WorkflowInstanceId workflowInstanceId, String stepName,
                         Instant dueAt, String targetNode, EventName emittedEvent, String status) {
        this(timerId, workflowInstanceId, stepName, dueAt, targetNode, emittedEvent,
                status == null || status.isBlank() ? WorkflowTimerStatus.PENDING : WorkflowTimerStatus.valueOf(status),
                0, dueAt, null, null, Instant.now(), Instant.now());
    }

    public WorkflowTimer fired() {
        return new WorkflowTimer(timerId, workflowInstanceId, stepName, dueAt, targetNode, emittedEvent,
                WorkflowTimerStatus.FIRED, attemptCount, nextAttemptAt, null, null, createdAt, Instant.now());
    }

    public WorkflowTimer canceled() {
        return new WorkflowTimer(timerId, workflowInstanceId, stepName, dueAt, targetNode, emittedEvent,
                WorkflowTimerStatus.CANCELED, attemptCount, nextAttemptAt, null, null, createdAt, Instant.now());
    }
}
