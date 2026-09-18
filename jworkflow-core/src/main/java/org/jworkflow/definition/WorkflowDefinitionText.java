package org.jworkflow.definition;


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
