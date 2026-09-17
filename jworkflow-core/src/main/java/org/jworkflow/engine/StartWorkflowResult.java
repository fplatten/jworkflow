package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StartWorkflowResult(
        UUID commandId,
        WorkflowInstanceId workflowInstanceId,
        String workflowKey,
        String workflowVersion,
        String businessKey,
        String correlationId,
        Instant acceptedAt,
        WorkflowSnapshot snapshot,
        List<String> emittedEventIds,
        boolean idempotentRepeat
) {
    public StartWorkflowResult {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(snapshot, "snapshot");
        emittedEventIds = emittedEventIds == null ? List.of() : List.copyOf(emittedEventIds);
    }

    public StartWorkflowResult asIdempotentRepeat() {
        return new StartWorkflowResult(
                commandId,
                workflowInstanceId,
                workflowKey,
                workflowVersion,
                businessKey,
                correlationId,
                acceptedAt,
                snapshot,
                emittedEventIds,
                true);
    }
}
