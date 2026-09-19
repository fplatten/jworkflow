package org.jworkflow.model;


import java.util.Objects;

/**
 * Validation code, optional node reference and explanation for an invalid workflow definition.
 * @param code stable diagnostic code
 * @param message human-readable diagnostic detail
 * @param location source location used for provenance and diagnostics
 */
public record DefinitionValidationError(
        String code,
        String message,
        String location
) {
    /**
     * Creates this value from the supplied components.
     * @param code stable diagnostic code
     * @param message human-readable diagnostic detail
     * @param location source location used for provenance and diagnostics
     * @throws NullPointerException if code, message is null
     */
    public DefinitionValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
