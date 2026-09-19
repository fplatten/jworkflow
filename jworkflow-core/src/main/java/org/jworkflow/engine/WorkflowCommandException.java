package org.jworkflow.engine;


/**
 * Base failure raised while validating or applying a workflow command.
 */
public class WorkflowCommandException extends RuntimeException {
    /** Stable machine-readable command failure category. */
    private final String errorCode;

    /**
     * Creates a workflow command exception with the supplied diagnostic context.
     * @param errorCode stable machine-readable failure category
     * @param message human-readable diagnostic detail
     */
    public WorkflowCommandException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a workflow command exception with the supplied diagnostic context.
     * @param errorCode stable machine-readable failure category
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public WorkflowCommandException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable machine-readable failure code.
     * @return the stable machine-readable failure code
     */
    public String errorCode() {
        return errorCode;
    }
}
