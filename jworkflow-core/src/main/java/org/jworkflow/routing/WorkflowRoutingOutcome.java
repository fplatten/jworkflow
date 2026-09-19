package org.jworkflow.routing;

/**
 * Resolution outcome distinguishing delivery, missing/ineligible targets and partial fan-out failure.
 */
public enum WorkflowRoutingOutcome {
    /**
     * Delivery succeeded for the selected targets.
     */
    ROUTED,
    /**
     * No instance matched the requested scope.
     */
    NO_MATCH,
    /**
     * More than one candidate matched a single-target route.
     */
    AMBIGUOUS,
    /**
     * A selected instance does not accept this event in its current state.
     */
    EVENT_NOT_ACCEPTED,
    /**
     * The targeted instance has already terminated.
     */
    TERMINAL_WORKFLOW,
    /**
     * Some fan-out targets succeeded and others failed.
     */
    PARTIAL_FAILURE
}
