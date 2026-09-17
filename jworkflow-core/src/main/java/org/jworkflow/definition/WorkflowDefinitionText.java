package org.jworkflow.definition;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Objects;

public record WorkflowDefinitionText(
        String location,
        String content
) {
    public WorkflowDefinitionText {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(content, "content");
    }
}
