package org.jworkflow.engine;

/** Result plus all durable effects calculated by the state machine. */
public record WorkflowTransitionResult<T>(T commandResult, WorkflowMutation mutation) {
    public WorkflowTransitionResult {
        if (commandResult == null) throw new IllegalArgumentException("commandResult is required");
        if (mutation == null) throw new IllegalArgumentException("mutation is required");
    }
}
