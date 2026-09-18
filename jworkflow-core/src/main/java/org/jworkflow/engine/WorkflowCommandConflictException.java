package org.jworkflow.engine;


public final class WorkflowCommandConflictException extends WorkflowCommandException {
    public WorkflowCommandConflictException(String message) {
        super("command_conflict", message);
    }
}
