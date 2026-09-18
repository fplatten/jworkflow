package org.jworkflow.model;

import org.jworkflow.events.*;

import java.util.List;

public record JoinDefinition(
        List<String> requiredBranches,
        String nextNode,
        EventName emittedEvent
) {
    public JoinDefinition {
        requiredBranches = requiredBranches == null ? List.of() : List.copyOf(requiredBranches);
        if (requiredBranches.isEmpty()) {
            throw new IllegalArgumentException("join requires at least one branch");
        }
        if (nextNode == null || nextNode.isBlank()) {
            throw new IllegalArgumentException("nextNode is required");
        }
    }
}
