package org.jworkflow.engine;


/**
 * An infrastructure operation failed while creating, loading or persisting workflow state. Inspect the cause; this
 * does not authorize automatic handler replay.
 */
public final class WorkflowInfrastructureException extends WorkflowCommandException {
    /**
     * Creates a workflow infrastructure exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public WorkflowInfrastructureException(String message, Throwable cause) {
        super("infrastructure_failure", message, cause);
    }
}
