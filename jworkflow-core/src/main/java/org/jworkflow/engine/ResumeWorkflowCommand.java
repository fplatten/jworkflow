package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

/**
 * Requests continuation of an existing workflow through the engine command boundary; the engine validates whether
 *  its current state can resume.
 * @param instanceId workflow instance identity
 * @param metadata command identity, audit context and optional replay key
 */
public record ResumeWorkflowCommand(
        WorkflowInstanceId instanceId,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param metadata command identity, audit context and optional replay key
     * @throws NullPointerException if instanceId is null
     */
    public ResumeWorkflowCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, null)
                : metadata;
    }
}
