package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
