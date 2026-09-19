package org.jworkflow.engine;


import java.util.Map;
import java.util.Objects;
import org.jworkflow.model.ImmutableData;

/**
 * Requests a new instance of a registered workflow, with its business identity and initial variables. A null
 *  version allows the engine to select the registered version. Null metadata is replaced with default command
 *  metadata.
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion workflow definition version; null selects the latest registered version
 * @param businessKey application business identity associated with the workflow
 * @param variables workflow variable values; durable values must follow the supported JSON value model
 * @param metadata command identity, audit context and optional replay key
 */
public record StartWorkflowCommand(
        String workflowKey,
        String workflowVersion,
        String businessKey,
        Map<String, Object> variables,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    /**
     * Creates this value from the supplied components.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion workflow definition version; null selects the latest registered version
     * @param businessKey application business identity associated with the workflow
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @param metadata command identity, audit context and optional replay key
     * @throws NullPointerException if workflowKey, businessKey is null
     */
    public StartWorkflowCommand {
        Objects.requireNonNull(workflowKey, "workflowKey");
        Objects.requireNonNull(businessKey, "businessKey");
        variables = ImmutableData.copyStringObjectMap(variables);
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(workflowKey, workflowVersion, null, businessKey)
                : metadata;
    }
}
