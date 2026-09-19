package org.jworkflow.engine;

/**
 * Result plus all durable effects calculated by the state machine.
 * @param <T> the value type
 * @param commandResult result returned to the command caller
 * @param mutation calculated snapshot, event and timer effects
 */
public record WorkflowTransitionResult<T>(T commandResult, WorkflowMutation mutation) {
    /**
     * Creates this value from the supplied components.
     * @param commandResult result returned to the command caller
     * @param mutation calculated snapshot, event and timer effects
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowTransitionResult {
        if (commandResult == null) throw new IllegalArgumentException("commandResult is required");
        if (mutation == null) throw new IllegalArgumentException("mutation is required");
    }
}
