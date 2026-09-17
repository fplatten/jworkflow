package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Map;
import java.util.Objects;
import org.jworkflow.model.ImmutableData;

public record StartWorkflowCommand(
        String workflowKey,
        String workflowVersion,
        String businessKey,
        Map<String, Object> variables,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    public StartWorkflowCommand {
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(businessKey, "businessKey");
        variables = ImmutableData.copyStringObjectMap(variables);
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(workflowKey, workflowVersion, null, businessKey)
                : metadata;
    }
}
