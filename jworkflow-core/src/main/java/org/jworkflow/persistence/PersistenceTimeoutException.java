package org.jworkflow.persistence;

/**
 * A persistence operation exceeded a configured deadline or lock wait.
 */
public final class PersistenceTimeoutException extends WorkflowPersistenceException {
    /**
     * Creates a persistence timeout exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public PersistenceTimeoutException(String message, Throwable cause) { super(message, cause); }
}
