package org.jworkflow.routing;

/**
 * A route lacks a supported identity or contradicts supplied workflow/tenant constraints.
 */
public final class InvalidWorkflowRouteException extends WorkflowRoutingException {
    /**
     * Creates a invalid workflow route exception with the supplied diagnostic context.
     * @param outcome event-routing resolution outcome
     * @param message human-readable diagnostic detail
     */
    public InvalidWorkflowRouteException(WorkflowRoutingOutcome outcome, String message) {
        super(outcome, message);
    }
}
