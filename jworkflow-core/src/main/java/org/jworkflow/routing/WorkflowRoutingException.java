package org.jworkflow.routing;

public class WorkflowRoutingException extends RuntimeException {
    private final WorkflowRoutingOutcome outcome;

    public WorkflowRoutingException(WorkflowRoutingOutcome outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    public WorkflowRoutingOutcome outcome() { return outcome; }
}
