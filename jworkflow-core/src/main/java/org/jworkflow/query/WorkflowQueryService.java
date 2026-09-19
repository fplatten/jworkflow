package org.jworkflow.query;
import org.jworkflow.model.WorkflowInstanceId;import java.time.Instant;import java.util.*;
/**
 * Read-only persistence queries and projection replay. Bounded list methods return observations, not
 * transactionally locked work. A complete timeline/replay loads an instance's history and is not a bounded
 * timeline cursor.
 */
public interface WorkflowQueryService{
 /**
  * Returns a bounded offset page of instance summaries; concurrent updates may change subsequent pages.
  * @param limit maximum number of rows requested
  * @param offset zero-based number of rows to skip
  * @return the matching values in the order defined by this operation
  */
 List<WorkflowInstanceSummary> list(int limit,int offset);
 /**
  * Looks up the stored value by its stable identity without treating absence as an error.
  * @param id identity of the value to look up or update
  * @return the matching value, or an empty optional when absent
  */
 Optional<WorkflowDetail> findById(WorkflowInstanceId id);
 /**
  * Looks up a workflow by its scoped workflow/business identity.
  * @param workflowKey registered workflow name used to resolve a definition
  * @param businessKey application business identity associated with the workflow
  * @return the matching value, or an empty optional when absent
  */
 Optional<WorkflowDetail> findByBusinessKey(String workflowKey,String businessKey);
 /**
  * Looks up a snapshot or detail by correlation identity; use explicit scoped routes when uniqueness matters.
  * @param correlationId identity shared by related commands and events
  * @return the matching value, or an empty optional when absent
  */
 Optional<WorkflowDetail> findByCorrelationId(String correlationId);
 /**
  * Returns at most the requested number of failed workflow summaries.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 List<WorkflowInstanceSummary> failed(int limit);
 /**
  * Finds active instances last updated at or before the supplied threshold; this is a diagnostic heuristic, not a
  * lease claim.
  * @param updatedBefore inclusive last-update threshold for stalled instances
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 List<WorkflowInstanceSummary> stuck(Instant updatedBefore,int limit);
 /**
  * Loads the instance's ordered event history. A full timeline is not bounded by a page size.
  * @param id identity of the value to look up or update
  * @return the resulting workflow timeline
  */
 WorkflowTimeline timeline(WorkflowInstanceId id);
 /**
  * Returns bounded observations of declared waits resolved from current snapshots and exact definitions.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 List<PendingWaitView> pendingWaits(int limit);
 /**
  * Returns bounded pending timer observations without acquiring claims.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 List<PendingTimerView> pendingTimers(int limit);
 /**
  * Returns bounded pending publication observations without sending messages.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 List<OutboxStateView> pendingOutbox(int limit);
 /**
  * Feeds ordered history to the supplied projection without changing authoritative workflow state; the projection
  * owns any external effects.
  * @param id identity of the value to look up or update
  * @param projection host projection consuming ordered history
  */
 void replay(WorkflowInstanceId id,WorkflowProjection projection);
}
