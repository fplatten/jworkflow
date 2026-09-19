package org.jworkflow.routing;

import org.jworkflow.model.WorkflowInstanceId;

import java.util.List;

/**
 * Immutable route outcome with eligible/delivered instance identities and failed-target details.
 * @param outcome event-routing resolution outcome
 * @param routedInstances instance identities receiving the routed event
 * @param failedInstances failed route targets and their failure descriptions
 * @param detail diagnostic or workflow detail associated with the observation
 */
public record WorkflowRoutingResult(
        WorkflowRoutingOutcome outcome,
        List<WorkflowInstanceId> routedInstances,
        List<WorkflowInstanceId> failedInstances,
        String detail
) {
    /**
     * Creates this value from the supplied components.
     * @param outcome event-routing resolution outcome
     * @param routedInstances instance identities receiving the routed event
     * @param failedInstances failed route targets and their failure descriptions
     * @param detail diagnostic or workflow detail associated with the observation
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowRoutingResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        routedInstances = routedInstances == null ? List.of() : List.copyOf(routedInstances);
        failedInstances = failedInstances == null ? List.of() : List.copyOf(failedInstances);
    }
}
