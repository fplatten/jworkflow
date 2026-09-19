package org.jworkflow.engine;


import java.util.Map;

/**
 * Handler success/failure decision with variable updates and optional error details. The state machine selects the
 * corresponding configured transition.
 * @param successful whether the attempt or handler succeeded
 * @param variables workflow variable values; durable values must follow the supported JSON value model
 * @param errorCode stable machine-readable failure category
 * @param errorMessage human-readable failure detail
 */
public record StepResult(
        boolean successful,
        Map<String, Object> variables,
        String errorCode,
        String errorMessage
) {
    /**
     * Creates this value from the supplied components.
     * @param successful whether the attempt or handler succeeded
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @param errorCode stable machine-readable failure category
     * @param errorMessage human-readable failure detail
     */
    public StepResult {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /**
     * Creates a successful handler result with the supplied variable updates, or no updates in the zero-argument
     * overload.
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @return the resulting step result
     */
    public static StepResult success(Map<String, Object> variables) {
        return new StepResult(true, variables, null, null);
    }

    /**
     * Creates a successful handler result with the supplied variable updates, or no updates in the zero-argument
     * overload.
     * @return the resulting step result
     */
    public static StepResult success() {
        return success(Map.of());
    }

    /**
     * Creates a failed handler result with an error code/message and no variable updates.
     * @param errorCode stable machine-readable failure category
     * @param errorMessage human-readable failure detail
     * @return the resulting step result
     */
    public static StepResult failure(String errorCode, String errorMessage) {
        return new StepResult(false, Map.of(), errorCode, errorMessage);
    }
}
