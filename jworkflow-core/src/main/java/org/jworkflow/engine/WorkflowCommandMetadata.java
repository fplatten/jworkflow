package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
    public WorkflowCommandMetadata {
        commandId = commandId == null ? UUID.randomUUID() : commandId;
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

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

    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    public CausationId causationIdentity() { return CausationId.of(causationId); }
    public TraceId traceIdentity() { return TraceId.of(traceId); }
}
