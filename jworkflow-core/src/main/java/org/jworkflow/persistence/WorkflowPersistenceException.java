package org.jworkflow.persistence;

/**
 * Base unchecked failure raised by a persistence adapter.
 */
public class WorkflowPersistenceException extends RuntimeException {
    /**
     * Creates a workflow persistence exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public WorkflowPersistenceException(String message) { super(message); }
    /**
     * Creates a workflow persistence exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public WorkflowPersistenceException(String message, Throwable cause) { super(message, cause); }
}
