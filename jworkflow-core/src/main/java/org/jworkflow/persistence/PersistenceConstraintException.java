package org.jworkflow.persistence;

/**
 * A persistence uniqueness, foreign-key or other integrity constraint was violated.
 */
public final class PersistenceConstraintException extends WorkflowPersistenceException {
    /**
     * Creates a persistence constraint exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public PersistenceConstraintException(String message) { super(message); }
    /**
     * Creates a persistence constraint exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public PersistenceConstraintException(String message, Throwable cause) { super(message, cause); }
}
