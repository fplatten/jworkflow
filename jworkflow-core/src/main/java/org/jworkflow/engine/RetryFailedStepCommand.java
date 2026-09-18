package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

public record RetryFailedStepCommand(
        WorkflowInstanceId instanceId,
        String stepId,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    public RetryFailedStepCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(stepId, "stepId");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, null)
                : metadata;
    }
}
