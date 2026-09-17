package org.jworkflow.routing;

public final class InvalidWorkflowRouteException extends WorkflowRoutingException {
    public InvalidWorkflowRouteException(WorkflowRoutingOutcome outcome, String message) {
        super(outcome, message);
    }
}
