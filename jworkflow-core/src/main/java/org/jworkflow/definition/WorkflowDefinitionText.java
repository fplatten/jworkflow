package org.jworkflow.definition;


import java.util.Objects;

/**
 * Source location and non-null DSL text submitted to the compiler; the location is used in diagnostics.
 * @param location source location used for provenance and diagnostics
 * @param content UTF-8 workflow source content
 */
public record WorkflowDefinitionText(
        String location,
        String content
) {
    /**
     * Creates this value from the supplied components.
     * @param location source location used for provenance and diagnostics
     * @param content UTF-8 workflow source content
     * @throws NullPointerException if location, content is null
     */
    public WorkflowDefinitionText {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(content, "content");
    }
}
