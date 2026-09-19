package org.jworkflow.definition;

import org.jworkflow.model.WorkflowDefinition;

import java.util.Objects;

/**
 * Pairs an accepted immutable workflow definition with the source provenance recorded at activation.
 * @param definition immutable workflow definition
 * @param source source provenance recorded on successful activation
 */
public record ActivatedWorkflowDefinition(
        WorkflowDefinition definition,
        WorkflowDefinitionSourceMetadata source
) {
    /**
     * Creates this value from the supplied components.
     * @param definition immutable workflow definition
     * @param source source provenance recorded on successful activation
     * @throws NullPointerException if definition, source is null
     */
    public ActivatedWorkflowDefinition {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(source, "source");
    }

    /**
     * Returns the activated workflow definition's name.
     * @return the activated workflow definition's name
     */
    public String workflowName() { return definition.name(); }
    /**
     * Returns the activated workflow definition's version.
     * @return the activated workflow definition's version
     */
    public String workflowVersion() { return definition.version(); }
    /**
     * Returns the exact semantic revision of the activated definition.
     * @return the exact semantic revision of the activated definition
     */
    public String revision() { return definition.revision(); }
}
