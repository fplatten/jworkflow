package org.jworkflow.model;

import org.jworkflow.engine.*;

import java.util.List;

public record DefinitionValidationResult(List<DefinitionValidationError> errors) {
    public DefinitionValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public void throwIfInvalid() {
        if (!valid()) {
            throw new WorkflowValidationException(errors.stream()
                    .map(error -> error.code() + ": " + error.message())
                    .toList());
        }
    }
}
