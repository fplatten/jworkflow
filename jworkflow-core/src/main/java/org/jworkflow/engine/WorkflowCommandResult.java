package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record WorkflowCommandResult(
        UUID commandId,
        WorkflowInstanceId workflowInstanceId,
        WorkflowCommandStatus status,
        WorkflowSnapshot snapshot,
        List<String> emittedEventIds,
        List<String> eventStatusAttemptIds,
        boolean idempotentRepeat
) {
    public WorkflowCommandResult {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(status, "status");
        emittedEventIds = emittedEventIds == null ? List.of() : List.copyOf(emittedEventIds);
        eventStatusAttemptIds = eventStatusAttemptIds == null ? List.of() : List.copyOf(eventStatusAttemptIds);
    }

    public WorkflowCommandResult asIdempotentRepeat() {
        return new WorkflowCommandResult(
                commandId,
                workflowInstanceId,
                status,
                snapshot,
                emittedEventIds,
                eventStatusAttemptIds,
                true);
    }
}
