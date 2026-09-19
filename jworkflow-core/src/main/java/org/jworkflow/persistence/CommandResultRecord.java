package org.jworkflow.persistence;

import org.jworkflow.model.ImmutableData;
import org.jworkflow.model.WorkflowInstanceId;

import java.time.Instant;
import java.util.Map;

/**
 * Persisted command type, complete idempotency key, request hash and original result data. Duplicate keys must
 * validate type and content rather than execute another command.
 * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
 *     outcome
 * @param commandType stored command kind used to validate idempotent replay
 * @param requestHash fingerprint of the immutable command input
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param result result data associated with the operation
 * @param createdAt creation time
 */
public record CommandResultRecord(
        String idempotencyKey,
        String commandType,
        String requestHash,
        WorkflowInstanceId workflowInstanceId,
        Map<String, Object> result,
        Instant createdAt
) {
    /**
     * Creates this value from the supplied components.
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *     outcome
     * @param commandType stored command kind used to validate idempotent replay
     * @param requestHash fingerprint of the immutable command input
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param result result data associated with the operation
     * @param createdAt creation time
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public CommandResultRecord {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        if (commandType == null || commandType.isBlank()) throw new IllegalArgumentException("commandType is required");
        if (requestHash == null || requestHash.isBlank()) throw new IllegalArgumentException("requestHash is required");
        result = ImmutableData.copyStringObjectMap(result);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
