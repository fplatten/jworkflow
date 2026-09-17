package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
