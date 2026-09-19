package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

/**
 * Requests cancellation of an existing workflow instance. Command metadata carries the caller identity and
 *  optional replay key.
 * @param instanceId workflow instance identity
 * @param metadata command identity, audit context and optional replay key
 */
public record CancelWorkflowCommand(
        WorkflowInstanceId instanceId,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param metadata command identity, audit context and optional replay key
     * @throws NullPointerException if instanceId is null
     */
    public CancelWorkflowCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, null)
                : metadata;
    }
}
