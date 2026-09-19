package org.jworkflow.engine;


import java.util.List;

/**
 * Command input or workflow data failed validation before it could be applied.
 */
public final class WorkflowValidationException extends WorkflowCommandException {
    /** Validation failures that prevented the command from executing. */
    private final List<String> violations;

    /**
     * Creates a workflow validation exception with the supplied diagnostic context.
     * @param violations validation violations preventing the command
     */
    public WorkflowValidationException(List<String> violations) {
        super("validation", "Workflow command validation failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the validation violations that prevented the operation.
     * @return the matching values in the order defined by this operation
     */
    public List<String> violations() {
        return violations;
    }
}
