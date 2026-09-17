package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
