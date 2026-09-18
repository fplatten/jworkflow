package org.jworkflow.engine;


import java.util.List;

public final class WorkflowValidationException extends WorkflowCommandException {
    private final List<String> violations;

    public WorkflowValidationException(List<String> violations) {
        super("validation", "Workflow command validation failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
