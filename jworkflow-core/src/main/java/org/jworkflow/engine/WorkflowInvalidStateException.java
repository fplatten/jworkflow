package org.jworkflow.engine;


public final class WorkflowInvalidStateException extends WorkflowCommandException {
    public WorkflowInvalidStateException(String message) {
        super("invalid_state", message);
    }
}
