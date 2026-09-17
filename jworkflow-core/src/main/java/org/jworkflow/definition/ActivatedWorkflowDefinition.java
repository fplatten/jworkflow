package org.jworkflow.definition;

import org.jworkflow.model.WorkflowDefinition;

import java.util.Objects;

public record ActivatedWorkflowDefinition(
        WorkflowDefinition definition,
        WorkflowDefinitionSourceMetadata source
) {
    public ActivatedWorkflowDefinition {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(source, "source");
    }

    public String workflowName() { return definition.name(); }
    public String workflowVersion() { return definition.version(); }
    public String revision() { return definition.revision(); }
}
