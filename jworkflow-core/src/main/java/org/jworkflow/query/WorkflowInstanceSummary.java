package org.jworkflow.query;

import org.jworkflow.model.*;
import java.time.Instant;

/**
 * Compact immutable instance identity, status and timing view for bounded list queries.
 * @param instanceId workflow instance identity
 * @param workflowKey registered workflow name used to resolve a definition
 * @param workflowVersion workflow definition version
 * @param workflowRevision exact immutable definition revision pinned by the workflow
 * @param businessKey application business identity associated with the workflow
 * @param correlationId identity shared by related commands and events
 * @param state current workflow node name
 * @param status instance lifecycle state
 * @param lockVersion nonnegative optimistic snapshot version
 * @param createdAt creation time
 * @param updatedAt last recorded update time
 */
public record WorkflowInstanceSummary(WorkflowInstanceId instanceId,String workflowKey,String workflowVersion,
 String workflowRevision,String businessKey,String correlationId,String state,WorkflowStatus status,long lockVersion,
 Instant createdAt,Instant updatedAt){
 /**
  * Copies identity, lifecycle and concurrency metadata from the stored snapshot.
  * @param s the immutable instance snapshot
  * @return the resulting workflow instance summary
  */
 public static WorkflowInstanceSummary from(WorkflowSnapshot s){return new WorkflowInstanceSummary(s.instanceId(),s.workflowKey(),s.workflowVersion(),s.workflowRevision(),s.businessKey(),s.correlationId(),s.state(),s.status(),s.lockVersion(),s.createdAt(),s.updatedAt());}
}
