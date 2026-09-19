package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of accepting a workflow start, including its instance identity, command-time snapshot and emitted event
 * identifiers. A replay returns the stored command result; query the engine separately for current state.
 * @param commandId command identity; constructors accepting null generate an identity
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion workflow definition version
 * @param businessKey application business identity associated with the workflow
 * @param correlationId identity shared by related commands and events
 * @param acceptedAt time at which the command was accepted
 * @param snapshot point-in-time workflow snapshot
 * @param emittedEventIds identifiers of the events emitted by the original command
 * @param idempotentRepeat whether this result replays an already accepted command
 */
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
    /**
     * Creates this value from the supplied components.
     * @param commandId command identity; constructors accepting null generate an identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion workflow definition version
     * @param businessKey application business identity associated with the workflow
     * @param correlationId identity shared by related commands and events
     * @param acceptedAt time at which the command was accepted
     * @param snapshot point-in-time workflow snapshot
     * @param emittedEventIds identifiers of the events emitted by the original command
     * @param idempotentRepeat whether this result replays an already accepted command
     * @throws NullPointerException if commandId, workflowInstanceId, workflowKey, businessKey, acceptedAt,
     *     snapshot is null
     */
    public StartWorkflowResult {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(snapshot, "snapshot");
        emittedEventIds = emittedEventIds == null ? List.of() : List.copyOf(emittedEventIds);
    }

    /**
     * Returns a copy of the original result with the replay marker set; no workflow work is executed.
     * @return a copy of the original result with the replay marker set; no workflow work is executed
     */
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
