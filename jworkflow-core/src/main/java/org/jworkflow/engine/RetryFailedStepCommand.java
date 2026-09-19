package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

/**
 * Requests another execution of a failed step in an existing instance. A retry can repeat nontransactional handler
 *  effects.
 * @param instanceId workflow instance identity
 * @param stepId failed step to retry; null where the engine may infer it
 * @param metadata command identity, audit context and optional replay key
 */
public record RetryFailedStepCommand(
        WorkflowInstanceId instanceId,
        String stepId,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param stepId failed step to retry; null where the engine may infer it
     * @param metadata command identity, audit context and optional replay key
     * @throws NullPointerException if instanceId, stepId is null
     */
    public RetryFailedStepCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(stepId, "stepId");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, null)
                : metadata;
    }
}
