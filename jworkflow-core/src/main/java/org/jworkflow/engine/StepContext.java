package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Map;
import java.util.Objects;

/**
 * Read-only handler inputs: instance/workflow/business identity, current step/action, immutable variables and
 * optional triggering signal.
 * @param instanceId workflow instance identity
 * @param workflowKey registered workflow name used to resolve a definition
 * @param businessKey application business identity associated with the workflow
 * @param stepName workflow node name for this step
 * @param action registered handler action name
 * @param variables workflow variable values; durable values must follow the supported JSON value model
 * @param signal triggering signal and its original message envelope
 */
public record StepContext(
        WorkflowInstanceId instanceId,
        String workflowKey,
        String businessKey,
        String stepName,
        String action,
        Map<String, Object> variables,
        WorkflowSignal signal
) {
    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @param stepName workflow node name for this step
     * @param action registered handler action name
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @param signal triggering signal and its original message envelope
     * @throws NullPointerException if instanceId, workflowKey, businessKey, stepName, action is null
     */
    public StepContext {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(action, "action");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
