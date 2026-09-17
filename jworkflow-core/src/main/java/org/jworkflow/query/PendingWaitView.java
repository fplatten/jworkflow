package org.jworkflow.query;
import org.jworkflow.model.WorkflowInstanceId;
public record PendingWaitView(WorkflowInstanceId instanceId,String workflowKey,String workflowVersion,String state,
 String eventName,String correlateBy,String businessKey,String correlationId){}
