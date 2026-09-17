package org.jworkflow.persistence;

public final class PersistenceSerializationException extends WorkflowPersistenceException {
    public PersistenceSerializationException(String message) { super(message); }
    public PersistenceSerializationException(String message, Throwable cause) { super(message, cause); }
}
