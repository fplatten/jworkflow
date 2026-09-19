package org.jworkflow.engine;


/**
 * The requested command is not valid for the instance's current state.
 */
public final class WorkflowInvalidStateException extends WorkflowCommandException {
    /**
     * Creates a workflow invalid state exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public WorkflowInvalidStateException(String message) {
        super("invalid_state", message);
    }
}
