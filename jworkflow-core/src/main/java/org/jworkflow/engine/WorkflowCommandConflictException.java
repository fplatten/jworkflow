package org.jworkflow.engine;


/**
 * Base command conflict indicating incompatible concurrent or repeated work.
 */
public final class WorkflowCommandConflictException extends WorkflowCommandException {
    /**
     * Creates a workflow command conflict exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public WorkflowCommandConflictException(String message) {
        super("command_conflict", message);
    }
}
