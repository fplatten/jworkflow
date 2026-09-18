package org.jworkflow.engine;


public final class WorkflowDefinitionNotFoundException extends WorkflowCommandException {
    public WorkflowDefinitionNotFoundException(String workflowKey, String workflowVersion) {
        super("definition_not_found", "Unknown workflow definition: " + workflowKey + ":" + workflowVersion);
    }
}
