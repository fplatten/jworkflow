package org.jworkflow.model;

import org.jworkflow.engine.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;

/**
 * Evaluates declarative branch comparisons and explicitly registered predicates. Predicate code remains
 * application-owned and may execute inside a workflow transaction.
 */
public final class BranchConditionEvaluator {
    /** Creates an evaluator with no registered host predicates. */
    public BranchConditionEvaluator() {
    }

    private final ConcurrentMap<String, BiPredicate<Map<String, Object>, Map<String, Object>>> predicates = new ConcurrentHashMap<>();

    /**
     * Registers a named host predicate and returns this evaluator for configuration.
     * @param name name used to invoke this registered predicate
     * @param predicate host predicate invoked with workflow variables and configured arguments
     * @return the resulting branch condition evaluator
     * @throws NullPointerException if predicate is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public BranchConditionEvaluator registerPredicate(
            String name,
            BiPredicate<Map<String, Object>, Map<String, Object>> predicate
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("predicate name is required");
        }
        predicates.put(name, Objects.requireNonNull(predicate, "predicate"));
        return this;
    }

    /**
     * Evaluates the configured variable comparison or named predicate against the supplied variables.
     * @param condition declarative comparison or named predicate
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @return true when the condition described above holds; false otherwise
     * @throws NullPointerException if condition is null
     */
    public boolean evaluate(BranchCondition condition, Map<String, Object> variables) {
        Objects.requireNonNull(condition, "condition");
        Map<String, Object> safeVariables = variables == null ? Map.of() : Map.copyOf(variables);
        if (condition.predicate() != null && !condition.predicate().isBlank()) {
            BiPredicate<Map<String, Object>, Map<String, Object>> predicate = predicates.get(condition.predicate());
            if (predicate == null) {
                throw new WorkflowValidationException(
                        java.util.List.of("Unknown branch predicate: " + condition.predicate()));
            }
            return predicate.test(safeVariables, condition.arguments());
        }

        Object actual = safeVariables.get(condition.variable());
        Object expected = condition.value();
        return switch (condition.operator()) {
            case "eq" -> Objects.equals(actual, expected);
            case "ne", "neq" -> !Objects.equals(actual, expected);
            case "gt" -> compare(actual, expected) > 0;
            case "gte" -> compare(actual, expected) >= 0;
            case "lt" -> compare(actual, expected) < 0;
            case "lte" -> compare(actual, expected) <= 0;
            case "present" -> actual != null;
            case "absent" -> actual == null;
            default -> throw new WorkflowValidationException(
                    java.util.List.of("Unsupported branch operator: " + condition.operator()));
        };
    }

    private static int compare(Object actual, Object expected) {
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            return new BigDecimal(actualNumber.toString()).compareTo(new BigDecimal(expectedNumber.toString()));
        }
        if (actual instanceof Comparable<?> comparable && actual.getClass().isInstance(expected)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> typed = (Comparable<Object>) comparable;
            return typed.compareTo(expected);
        }
        throw new WorkflowValidationException(
                java.util.List.of("Branch comparison requires comparable values"));
    }
}
