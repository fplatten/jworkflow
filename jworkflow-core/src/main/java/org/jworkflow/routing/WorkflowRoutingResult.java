package org.jworkflow.routing;

import org.jworkflow.model.WorkflowInstanceId;

import java.util.List;

public record WorkflowRoutingResult(
        WorkflowRoutingOutcome outcome,
        List<WorkflowInstanceId> routedInstances,
        List<WorkflowInstanceId> failedInstances,
        String detail
) {
    public WorkflowRoutingResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        routedInstances = routedInstances == null ? List.of() : List.copyOf(routedInstances);
        failedInstances = failedInstances == null ? List.of() : List.copyOf(failedInstances);
    }
}
