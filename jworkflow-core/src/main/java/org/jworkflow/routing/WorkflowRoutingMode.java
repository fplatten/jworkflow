package org.jworkflow.routing;

/**
 * Selects a single eligible target or explicitly requested fan-out.
 */
public enum WorkflowRoutingMode {
    /**
     * Requires one eligible route target; ambiguous candidates are rejected.
     */
    SINGLE,
    /**
     * Allows delivery to multiple eligible targets in the route scope.
     */
    FAN_OUT
}
