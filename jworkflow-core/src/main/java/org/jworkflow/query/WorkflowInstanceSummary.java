package org.jworkflow.query;

import org.jworkflow.model.*;
import java.time.Instant;

public record WorkflowInstanceSummary(WorkflowInstanceId instanceId,String workflowKey,String workflowVersion,
 String workflowRevision,String businessKey,String correlationId,String state,WorkflowStatus status,long lockVersion,
 Instant createdAt,Instant updatedAt){
 public static WorkflowInstanceSummary from(WorkflowSnapshot s){return new WorkflowInstanceSummary(s.instanceId(),s.workflowKey(),s.workflowVersion(),s.workflowRevision(),s.businessKey(),s.correlationId(),s.state(),s.status(),s.lockVersion(),s.createdAt(),s.updatedAt());}
}
