package org.jworkflow.engine;


import java.util.Map;

public record StepResult(
        boolean successful,
        Map<String, Object> variables,
        String errorCode,
        String errorMessage
) {
    public StepResult {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static StepResult success(Map<String, Object> variables) {
        return new StepResult(true, variables, null, null);
    }

    public static StepResult success() {
        return success(Map.of());
    }

    public static StepResult failure(String errorCode, String errorMessage) {
        return new StepResult(false, Map.of(), errorCode, errorMessage);
    }
}
