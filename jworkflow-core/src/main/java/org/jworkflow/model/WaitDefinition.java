package org.jworkflow.model;

import org.jworkflow.events.EventName;

/**
 * External event name, correlation field and continuation target for a wait node.
 * @param eventName event name matched by workflow transitions or subscribers
 * @param correlateBy variable or metadata field used to correlate the expected event
 * @param targetNode node to enter after the transition
 */
public record WaitDefinition(
        EventName eventName,
        String correlateBy,
        String targetNode
) {
    /**
     * Creates this value from the supplied components.
     * @param eventName event name matched by workflow transitions or subscribers
     * @param correlateBy variable or metadata field used to correlate the expected event
     * @param targetNode node to enter after the transition
     * @throws NullPointerException if eventName is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WaitDefinition {
        java.util.Objects.requireNonNull(eventName, "eventName");
        if (targetNode == null || targetNode.isBlank()) {
            throw new IllegalArgumentException("targetNode is required");
        }
    }
}
