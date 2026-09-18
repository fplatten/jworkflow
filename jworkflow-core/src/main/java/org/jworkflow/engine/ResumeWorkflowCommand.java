package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

public record ResumeWorkflowCommand(
        WorkflowInstanceId instanceId,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    public ResumeWorkflowCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, null)
                : metadata;
    }
}
