package org.jworkflow.engine;


/**
 * No registered or persisted definition matches the requested workflow identity.
 */
public final class WorkflowDefinitionNotFoundException extends WorkflowCommandException {
    /**
     * Creates a workflow definition not found exception with the supplied diagnostic context.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowVersion workflow definition version
     */
    public WorkflowDefinitionNotFoundException(String workflowKey, String workflowVersion) {
        super("definition_not_found", "Unknown workflow definition: " + workflowKey + ":" + workflowVersion);
    }
}
