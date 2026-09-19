package org.jworkflow.model;

import org.jworkflow.engine.*;

import java.util.List;

/**
 * Immutable collection of definition errors with helpers for validation gates.
 * @param errors validation error details
 */
public record DefinitionValidationResult(List<DefinitionValidationError> errors) {
    /**
     * Creates this value from the supplied components.
     * @param errors validation error details
     */
    public DefinitionValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * Tests whether validation collected no errors.
     * @return true when the condition described above holds; false otherwise
     */
    public boolean valid() {
        return errors.isEmpty();
    }

    /**
     * Throws a validation exception when errors are present; otherwise returns normally.
     */
    public void throwIfInvalid() {
        if (!valid()) {
            throw new WorkflowValidationException(errors.stream()
                    .map(error -> error.code() + ": " + error.message())
                    .toList());
        }
    }
}
