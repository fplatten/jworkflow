package org.jworkflow.model;


import java.util.Map;
import java.util.Objects;

/**
 * Declarative comparison of a workflow variable, or invocation of a named host predicate with immutable arguments.
 * @param variable workflow variable name
 * @param operator comparison operator; required when a variable name is supplied
 * @param value literal comparison value
 * @param predicate registered predicate name
 * @param arguments named immutable arguments supplied to a registered predicate
 */
public record BranchCondition(
        String variable,
        String operator,
        Object value,
        String predicate,
        Map<String, Object> arguments
) {
    /**
     * Creates this value from the supplied components.
     * @param variable workflow variable name
     * @param operator comparison operator; required when a variable name is supplied
     * @param value literal comparison value
     * @param predicate registered predicate name
     * @param arguments named immutable arguments supplied to a registered predicate
     * @throws NullPointerException if a variable is supplied without an operator
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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
