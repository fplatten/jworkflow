package org.jworkflow.persistence;

public final class PersistenceTimeoutException extends WorkflowPersistenceException {
    public PersistenceTimeoutException(String message, Throwable cause) { super(message, cause); }
}
