package org.jworkflow.persistence;

public final class PersistenceConstraintException extends WorkflowPersistenceException {
    public PersistenceConstraintException(String message) { super(message); }
    public PersistenceConstraintException(String message, Throwable cause) { super(message, cause); }
}
