package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Map;
import java.util.Objects;

public record BranchCondition(
        String variable,
        String operator,
        Object value,
        String predicate,
        Map<String, Object> arguments
) {
    public BranchCondition {
        if ((variable == null || variable.isBlank()) && (predicate == null || predicate.isBlank())) {
            throw new IllegalArgumentException("Branch condition requires a variable or registered predicate");
        }
        if (variable != null && !variable.isBlank()) {
            Objects.requireNonNull(operator, "operator");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
