package org.jworkflow.routing;

/**
 * A single-target route matched multiple eligible instances; no arbitrary winner is selected.
 */
public final class AmbiguousWorkflowRouteException extends WorkflowRoutingException {
    /**
     * Creates a ambiguous workflow route exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public AmbiguousWorkflowRouteException(String message) {
        super(WorkflowRoutingOutcome.AMBIGUOUS, message);
    }
}
