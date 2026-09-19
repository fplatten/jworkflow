package org.jworkflow.persistence;

/**
 * A value cannot be encoded or decoded under the persisted JSON/binary/time contract without loss.
 */
public final class PersistenceSerializationException extends WorkflowPersistenceException {
    /**
     * Creates a persistence serialization exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public PersistenceSerializationException(String message) { super(message); }
    /**
     * Creates a persistence serialization exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public PersistenceSerializationException(String message, Throwable cause) { super(message, cause); }
}
