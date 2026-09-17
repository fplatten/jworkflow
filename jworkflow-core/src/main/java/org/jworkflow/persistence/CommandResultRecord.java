package org.jworkflow.persistence;

import org.jworkflow.model.ImmutableData;
import org.jworkflow.model.WorkflowInstanceId;

import java.time.Instant;
import java.util.Map;

public record CommandResultRecord(
        String idempotencyKey,
        String commandType,
        String requestHash,
        WorkflowInstanceId workflowInstanceId,
        Map<String, Object> result,
        Instant createdAt
) {
    public CommandResultRecord {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        if (commandType == null || commandType.isBlank()) throw new IllegalArgumentException("commandType is required");
        if (requestHash == null || requestHash.isBlank()) throw new IllegalArgumentException("requestHash is required");
        result = ImmutableData.copyStringObjectMap(result);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
