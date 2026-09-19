package org.jworkflow.model;

import org.jworkflow.events.*;

import java.util.Objects;

/**
 * Named node transition with a target and optional emitted event.
 * @param name transition selector name, or null for an unconditional transition
 * @param targetNode node to enter after the transition
 * @param condition declarative comparison or named predicate
 * @param emittedEvent event emitted by the transition when configured
 */
public record WorkflowTransition(
        String name,
        String targetNode,
        BranchCondition condition,
        EventName emittedEvent
) {
    /**
     * Creates this value from the supplied components.
     * @param name transition selector name, or null for an unconditional transition
     * @param targetNode node to enter after the transition
     * @param condition declarative comparison or named predicate
     * @param emittedEvent event emitted by the transition when configured
     * @throws NullPointerException if targetNode is null
     */
    public WorkflowTransition {
        Objects.requireNonNull(targetNode, "targetNode");
    }

    /**
     * Creates an unconditional transition to the supplied node without an emitted event.
     * @param targetNode node to enter after the transition
     * @return the resulting workflow transition
     */
    public static WorkflowTransition goTo(String targetNode) {
        return new WorkflowTransition(null, targetNode, null, null);
    }
}
