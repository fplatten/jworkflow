package org.jworkflow.model;

import org.jworkflow.events.*;

import java.util.Map;
import java.util.Objects;

/**
 * Child workflow/version reference and input-variable mapping for a sub-workflow node.
 * @param workflowName workflow definition name
 * @param workflowVersion nonblank workflow definition version
 * @param inputMappings parent-variable to child-input mapping
 * @param successEvent event emitted on child-workflow success, when configured
 * @param failureEvent event emitted on child-workflow failure, when configured
 * @param successTargetNode continuation node after child-workflow success
 * @param failureTargetNode continuation node after child-workflow failure
 */
public record SubWorkflowDefinition(
        String workflowName,
        String workflowVersion,
        Map<String, String> inputMappings,
        EventName successEvent,
        EventName failureEvent,
        String successTargetNode,
        String failureTargetNode
) {
    /**
     * Creates this value from the supplied components.
     * @param workflowName workflow definition name
     * @param workflowVersion nonblank workflow definition version
     * @param inputMappings parent-variable to child-input mapping
     * @param successEvent event emitted on child-workflow success, when configured
     * @param failureEvent event emitted on child-workflow failure, when configured
     * @param successTargetNode continuation node after child-workflow success
     * @param failureTargetNode continuation node after child-workflow failure
     * @throws NullPointerException if successEvent, failureEvent is null
     */
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
