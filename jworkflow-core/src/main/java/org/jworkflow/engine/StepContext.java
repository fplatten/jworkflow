package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Map;
import java.util.Objects;

public record StepContext(
        WorkflowInstanceId instanceId,
        String workflowKey,
        String businessKey,
        String stepName,
        String action,
        Map<String, Object> variables,
        WorkflowSignal signal
) {
    public StepContext {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(action, "action");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
