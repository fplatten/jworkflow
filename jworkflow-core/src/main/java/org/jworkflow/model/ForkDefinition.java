package org.jworkflow.model;


import java.util.Map;

public record ForkDefinition(
        Map<String, String> branches,
        String joinNode
) {
    public ForkDefinition {
        branches = branches == null ? Map.of() : Map.copyOf(branches);
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("fork requires at least one branch");
        }
        if (joinNode == null || joinNode.isBlank()) {
            throw new IllegalArgumentException("joinNode is required");
        }
    }
}
