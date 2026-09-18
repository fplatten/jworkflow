package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

public record CancelWorkflowCommand(
        WorkflowInstanceId instanceId,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    public CancelWorkflowCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, null)
                : metadata;
    }
}
