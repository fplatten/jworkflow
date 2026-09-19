package org.jworkflow.engine;

import org.jworkflow.events.*;
import org.jworkflow.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Command identity, routing context and audit metadata. Null command IDs and request times are generated at
 * construction; null headers become an empty immutable map. A null idempotency key does not request keyed replay
 * protection. Tenant metadata is not tenant-aware persistence or authorization.
 * @param commandId command identity; constructors accepting null generate an identity
 * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
 *     outcome
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion workflow definition version
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param businessKey application business identity associated with the workflow
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param traceId host-provided distributed tracing identity
 * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
 * @param sourceSystem external source identity used in routing, audit or inbox deduplication
 * @param requestedBy host-provided actor requesting the operation
 * @param requestedAt time at which the operation was requested
 * @param headers event or command headers; secrets should be removed by the configured capture policy
 */
public record WorkflowCommandMetadata(
        UUID commandId,
        String idempotencyKey,
        String workflowKey,
        String workflowVersion,
        WorkflowInstanceId workflowInstanceId,
        String businessKey,
        String correlationId,
        String causationId,
        String traceId,
        String tenantId,
        String sourceSystem,
        String requestedBy,
        Instant requestedAt,
        Map<String, String> headers
) {
    /**
     * Creates this value from the supplied components.
     * @param commandId command identity; constructors accepting null generate an identity
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *     outcome
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion workflow definition version
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param businessKey application business identity associated with the workflow
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param traceId host-provided distributed tracing identity
     * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
     * @param sourceSystem external source identity used in routing, audit or inbox deduplication
     * @param requestedBy host-provided actor requesting the operation
     * @param requestedAt time at which the operation was requested
     * @param headers event or command headers; secrets should be removed by the configured capture policy
     */
    public WorkflowCommandMetadata {
        commandId = commandId == null ? UUID.randomUUID() : commandId;
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * Creates command metadata with a generated command ID, current request time, source jworkflow and no
     * idempotency key.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion workflow definition version
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param businessKey application business identity associated with the workflow
     * @return the resulting workflow command metadata
     */
    public static WorkflowCommandMetadata defaults(
            String workflowKey,
            String workflowVersion,
            WorkflowInstanceId workflowInstanceId,
            String businessKey
    ) {
        return new WorkflowCommandMetadata(
                null,
                null,
                workflowKey,
                workflowVersion,
                workflowInstanceId,
                businessKey,
                null,
                null,
                null,
                null,
                "jworkflow",
                null,
                null,
                Map.of());
    }

    /**
     * Copies the value with the supplied non-null workflow identity, retaining its other metadata.
     * @param instanceId workflow instance identity
     * @return the resulting workflow command metadata
     * @throws NullPointerException if instanceId is null
     */
    public WorkflowCommandMetadata withWorkflowInstanceId(WorkflowInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return new WorkflowCommandMetadata(
                commandId,
                idempotencyKey,
                workflowKey,
                workflowVersion,
                instanceId,
                businessKey,
                correlationId,
                causationId,
                traceId,
                tenantId,
                sourceSystem,
                requestedBy,
                requestedAt,
                headers);
    }

    /**
     * Returns the correlation string as a typed identity, or null when absent.
     * @return the correlation string as a typed identity, or null when absent
     */
    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    /**
     * Returns the causation string as a typed identity, or null when absent.
     * @return the causation string as a typed identity, or null when absent
     */
    public CausationId causationIdentity() { return CausationId.of(causationId); }
    /**
     * Returns the trace string as a typed identity, or null when absent.
     * @return the trace string as a typed identity, or null when absent
     */
    public TraceId traceIdentity() { return TraceId.of(traceId); }
}
