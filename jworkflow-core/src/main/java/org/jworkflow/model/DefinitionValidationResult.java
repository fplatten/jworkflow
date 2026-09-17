package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
