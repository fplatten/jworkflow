package org.jworkflow.engine;


public class WorkflowCommandException extends RuntimeException {
    private final String errorCode;

    public WorkflowCommandException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkflowCommandException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
