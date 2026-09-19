package org.jworkflow.model;


import java.util.Map;

/**
 * Named outgoing branches and the join node that coordinates their completion.
 * @param branches named parallel or conditional branches
 * @param joinNode node coordinating branch completion
 */
public record ForkDefinition(
        Map<String, String> branches,
        String joinNode
) {
    /**
     * Creates this value from the supplied components.
     * @param branches named parallel or conditional branches
     * @param joinNode node coordinating branch completion
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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
