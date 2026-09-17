package org.jworkflow.persistence;

public class WorkflowPersistenceException extends RuntimeException {
    public WorkflowPersistenceException(String message) { super(message); }
    public WorkflowPersistenceException(String message, Throwable cause) { super(message, cause); }
}
