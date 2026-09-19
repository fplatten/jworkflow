package org.jworkflow.query;
import org.jworkflow.model.WorkflowInstanceId;
/**
 * Read-only description of a workflow currently waiting for an external event, reconstructed from its exact
 * definition revision.
 * @param instanceId workflow instance identity
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion workflow definition version
 * @param state current workflow node name
 * @param eventName event name matched by workflow transitions or subscribers
 * @param correlateBy variable or metadata field used to correlate the expected event
 * @param businessKey application business identity associated with the workflow
 * @param correlationId identity shared by related commands and events
 */
public record PendingWaitView(WorkflowInstanceId instanceId,String workflowKey,String workflowVersion,String state,
 String eventName,String correlateBy,String businessKey,String correlationId){}
