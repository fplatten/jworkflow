package org.jworkflow.routing;

public final class AmbiguousWorkflowRouteException extends WorkflowRoutingException {
    public AmbiguousWorkflowRouteException(String message) {
        super(WorkflowRoutingOutcome.AMBIGUOUS, message);
    }
}
