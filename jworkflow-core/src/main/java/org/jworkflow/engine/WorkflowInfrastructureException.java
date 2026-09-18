package org.jworkflow.engine;


public final class WorkflowInfrastructureException extends WorkflowCommandException {
    public WorkflowInfrastructureException(String message, Throwable cause) {
        super("infrastructure_failure", message, cause);
    }
}
