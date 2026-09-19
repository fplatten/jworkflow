package org.jworkflow.definition;


import java.util.List;

/**
 * Supplies source text and locations for explicit workflow activation. Implementations decide how sources are
 * discovered; loading alone does not activate definitions.
 */
public interface WorkflowDefinitionSource {
    /**
     * Loads definition source text and provenance without activating it.
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowDefinitionText> load();
}
