package org.jworkflow.query;
import org.jworkflow.model.WorkflowInstanceId;import java.time.Instant;import java.util.*;
public interface WorkflowQueryService{
 List<WorkflowInstanceSummary> list(int limit,int offset);
 Optional<WorkflowDetail> findById(WorkflowInstanceId id);
 Optional<WorkflowDetail> findByBusinessKey(String workflowKey,String businessKey);
 Optional<WorkflowDetail> findByCorrelationId(String correlationId);
 List<WorkflowInstanceSummary> failed(int limit);
 List<WorkflowInstanceSummary> stuck(Instant updatedBefore,int limit);
 WorkflowTimeline timeline(WorkflowInstanceId id);
 List<PendingWaitView> pendingWaits(int limit);
 List<PendingTimerView> pendingTimers(int limit);
 List<OutboxStateView> pendingOutbox(int limit);
 void replay(WorkflowInstanceId id,WorkflowProjection projection);
}
