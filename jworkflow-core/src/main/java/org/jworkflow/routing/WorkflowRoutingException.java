package org.jworkflow.routing;

/**
 * Base failure while resolving or delivering an explicit workflow event route.
 */
public class WorkflowRoutingException extends RuntimeException {
    /** Route resolution or delivery outcome associated with this failure. */
    private final WorkflowRoutingOutcome outcome;

    /**
     * Creates a workflow routing exception with the supplied diagnostic context.
     * @param outcome event-routing resolution outcome
     * @param message human-readable diagnostic detail
     */
    public WorkflowRoutingException(WorkflowRoutingOutcome outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    /**
     * Returns event-routing resolution outcome.
     * @return event-routing resolution outcome
     */
    public WorkflowRoutingOutcome outcome() { return outcome; }
}
