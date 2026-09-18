package org.jworkflow.model;

import org.jworkflow.engine.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;

public final class BranchConditionEvaluator {
    private final ConcurrentMap<String, BiPredicate<Map<String, Object>, Map<String, Object>>> predicates = new ConcurrentHashMap<>();

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
