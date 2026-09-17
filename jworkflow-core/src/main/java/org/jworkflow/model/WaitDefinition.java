package org.jworkflow.model;

import org.jworkflow.events.EventName;

public record WaitDefinition(
        EventName eventName,
        String correlateBy,
        String targetNode
) {
    public WaitDefinition {
        java.util.Objects.requireNonNull(eventName, "eventName");
        if (targetNode == null || targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode is required");
        }
    }
}
