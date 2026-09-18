package org.jworkflow.model;

import org.jworkflow.events.*;

import java.util.Map;
import java.util.Objects;

public record SubWorkflowDefinition(
        String workflowName,
        String workflowVersion,
        Map<String, String> inputMappings,
        EventName successEvent,
        EventName failureEvent,
        String successTargetNode,
        String failureTargetNode
) {
    public SubWorkflowDefinition {
        requireText(workflowName, "workflowName");
        requireText(workflowVersion, "workflowVersion");
        Objects.requireNonNull(successEvent, "successEvent");
        Objects.requireNonNull(failureEvent, "failureEvent");
        inputMappings = inputMappings == null ? Map.of() : Map.copyOf(inputMappings);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
