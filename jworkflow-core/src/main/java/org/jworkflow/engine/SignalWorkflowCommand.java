package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

public record SignalWorkflowCommand(
        WorkflowInstanceId instanceId,
        WorkflowSignal signal,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    public SignalWorkflowCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(signal, "signal");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, signal.businessKey())
                : metadata;
    }
}
