package org.jworkflow.model;


import java.util.Objects;

public record DefinitionValidationError(
        String code,
        String message,
        String location
) {
    public DefinitionValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
