package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
