package org.jworkflow.model;

import org.jworkflow.events.*;

import java.util.List;

/**
 * Required branch identities, continuation target and optional event emitted when the join completes.
 * @param requiredBranches branch identities required before continuing
 * @param nextNode continuation node after this operation completes
 * @param emittedEvent event emitted by the transition when configured
 */
public record JoinDefinition(
        List<String> requiredBranches,
        String nextNode,
        EventName emittedEvent
) {
    /**
     * Creates this value from the supplied components.
     * @param requiredBranches branch identities required before continuing
     * @param nextNode continuation node after this operation completes
     * @param emittedEvent event emitted by the transition when configured
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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
