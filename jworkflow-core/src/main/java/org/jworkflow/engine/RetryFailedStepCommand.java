package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
