package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Command outcome with the affected snapshot, emitted event IDs and replay marker. A successful replay describes
 *  the original command outcome, not a new mutation.
 * @param commandId command identity; constructors accepting null generate an identity
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param status command outcome
 * @param snapshot point-in-time workflow snapshot
 * @param emittedEventIds identifiers of the events emitted by the original command
 * @param eventStatusAttemptIds identifiers of the recorded status attempts
 * @param idempotentRepeat whether this result replays an already accepted command
 */
public record WorkflowCommandResult(
        UUID commandId,
        WorkflowInstanceId workflowInstanceId,
        WorkflowCommandStatus status,
        WorkflowSnapshot snapshot,
        List<String> emittedEventIds,
        List<String> eventStatusAttemptIds,
        boolean idempotentRepeat
) {
    /**
     * Creates this value from the supplied components.
     * @param commandId command identity; constructors accepting null generate an identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param status command outcome
     * @param snapshot point-in-time workflow snapshot
     * @param emittedEventIds identifiers of the events emitted by the original command
     * @param eventStatusAttemptIds identifiers of the recorded status attempts
     * @param idempotentRepeat whether this result replays an already accepted command
     * @throws NullPointerException if commandId, workflowInstanceId, status is null
     */
    public WorkflowCommandResult {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(status, "status");
        emittedEventIds = emittedEventIds == null ? List.of() : List.copyOf(emittedEventIds);
        eventStatusAttemptIds = eventStatusAttemptIds == null ? List.of() : List.copyOf(eventStatusAttemptIds);
    }

    /**
     * Returns a copy of the original result with the replay marker set; no workflow work is executed.
     * @return a copy of the original result with the replay marker set; no workflow work is executed
     */
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
