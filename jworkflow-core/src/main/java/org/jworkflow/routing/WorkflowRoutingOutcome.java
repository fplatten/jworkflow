package org.jworkflow.routing;

public enum WorkflowRoutingOutcome {
    ROUTED,
    NO_MATCH,
    AMBIGUOUS,
    EVENT_NOT_ACCEPTED,
    TERMINAL_WORKFLOW,
    PARTIAL_FAILURE
}
