package org.jworkflow.query;

import org.jworkflow.model.*;
    import org.jworkflow.persistence.*;
import java.time.Instant;
    import java.util.*;

/** Default mutation-free read model backed exclusively by persistence query ports. */
public final class PersistenceWorkflowQueryService implements WorkflowQueryService{
 private final WorkflowPersistence persistence;
 /**
  * Constructs PersistenceWorkflowQueryService with the supplied collaborators and configuration.
  * @param persistence repository bundle sharing a transaction boundary
  * @throws NullPointerException if persistence is null
  */
 public PersistenceWorkflowQueryService(WorkflowPersistence persistence){this.persistence=Objects.requireNonNull(persistence);
 }
 /**
  * Returns a bounded offset page of instance summaries; concurrent updates may change subsequent pages.
  * @param limit maximum number of rows requested
  * @param offset zero-based number of rows to skip
  * @return the matching values in the order defined by this operation
  */
 public List<WorkflowInstanceSummary> list(int limit,int offset){bounds(limit,offset);
     return persistence.instances().findAll(limit,offset).stream().map(WorkflowInstanceSummary::from).toList();
 }
 /**
  * Looks up the stored value by its stable identity without treating absence as an error.
  * @param id identity of the value to look up or update
  * @return the matching value, or an empty optional when absent
  * @throws NullPointerException if id is null
  */
 public Optional<WorkflowDetail> findById(WorkflowInstanceId id){return persistence.instances().findById(Objects.requireNonNull(id)).map(this::detail);
 }
 /**
  * Looks up a workflow by its scoped workflow/business identity.
  * @param workflowKey registered workflow name used to resolve a definition
  * @param businessKey application business identity associated with the workflow
  * @return the matching value, or an empty optional when absent
  */
 public Optional<WorkflowDetail> findByBusinessKey(String workflowKey,String businessKey){return persistence.instances().findByBusinessKey(workflowKey,businessKey).map(this::detail);
 }
 /**
  * Looks up a snapshot or detail by correlation identity; use explicit scoped routes when uniqueness matters.
  * @param correlationId identity shared by related commands and events
  * @return the matching value, or an empty optional when absent
  */
 public Optional<WorkflowDetail> findByCorrelationId(String correlationId){return persistence.instances().findByCorrelationId(correlationId).map(this::detail);
 }
 /**
  * Returns at most the requested number of failed workflow summaries.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 public List<WorkflowInstanceSummary> failed(int limit){positive(limit);
     return persistence.instances().findByStatus(WorkflowStatus.FAILED,limit).stream().map(WorkflowInstanceSummary::from).toList();
 }
 /**
  * Finds active instances last updated at or before the supplied threshold; this is a diagnostic heuristic, not a
  * lease claim.
  * @param before inclusive last-update threshold for stalled instances
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  * @throws NullPointerException if before is null
  */
 public List<WorkflowInstanceSummary> stuck(Instant before,int limit){Objects.requireNonNull(before);
     positive(limit);
     return persistence.instances().findStuck(before,limit).stream().map(WorkflowInstanceSummary::from).toList();
 }
 /**
  * Loads the instance's ordered event history. A full timeline is not bounded by a page size.
  * @param id identity of the value to look up or update
  * @return the resulting workflow timeline
  * @throws NullPointerException if id is null
  */
 public WorkflowTimeline timeline(WorkflowInstanceId id){Objects.requireNonNull(id);
     return new WorkflowTimeline(id,persistence.events().findByWorkflowInstance(id).stream().map(WorkflowTimelineEntry::from).toList());
 }
 /**
  * Returns bounded observations of declared waits resolved from current snapshots and exact definitions.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 public List<PendingWaitView> pendingWaits(int limit){positive(limit);
     ArrayList<PendingWaitView> result=new ArrayList<>();
     for(WorkflowSnapshot s:persistence.instances().findActive(limit)){pendingWait(s).ifPresent(result::add);
     if(result.size()==limit)break;
 }
    return List.copyOf(result);
 }
 /**
  * Returns bounded pending timer observations without acquiring claims.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 public List<PendingTimerView> pendingTimers(int limit){positive(limit);
     return persistence.timers().findPending(limit).stream().map(PendingTimerView::from).toList();
 }
 /**
  * Returns bounded pending publication observations without sending messages.
  * @param limit maximum number of rows requested
  * @return the matching values in the order defined by this operation
  */
 public List<OutboxStateView> pendingOutbox(int limit){positive(limit);
     return persistence.outbox().findPending(limit).stream().map(OutboxStateView::from).toList();
 }
 /**
  * Feeds ordered history to the supplied projection without changing authoritative workflow state; the projection
  * owns any external effects.
  * @param id identity of the value to look up or update
  * @param projection host projection consuming ordered history
  * @throws NullPointerException if id, projection is null
  */
 public void replay(WorkflowInstanceId id,WorkflowProjection projection){Objects.requireNonNull(projection);
     persistence.events().findByWorkflowInstance(Objects.requireNonNull(id)).forEach(projection::onEvent);
 }
 private WorkflowDetail detail(WorkflowSnapshot s){return new WorkflowDetail(s,pendingWait(s),persistence.timers().findByWorkflowInstance(s.instanceId()).stream().filter(t->pending(t.status())).map(PendingTimerView::from).toList(),timeline(s.instanceId()));
 }
 private Optional<PendingWaitView> pendingWait(WorkflowSnapshot s){if(s.status()==WorkflowStatus.COMPLETED||s.status()==WorkflowStatus.CANCELED)return Optional.empty();
     return persistence.definitions().findRevision(s.workflowKey(),s.workflowVersion(),s.workflowRevision()).map(d->d.nodes().get(s.state())).filter(Objects::nonNull).filter(n->n.type()==WorkflowNodeType.WAIT).map(n->new PendingWaitView(s.instanceId(),s.workflowKey(),s.workflowVersion(),s.state(),n.waitDefinition().eventName().value(),n.waitDefinition().correlateBy(),s.businessKey(),s.correlationId()));
 }
 private static boolean pending(WorkflowTimerStatus s){return s==WorkflowTimerStatus.PENDING||s==WorkflowTimerStatus.CLAIMED||s==WorkflowTimerStatus.RETRY_SCHEDULED;
 }
 private static void bounds(int limit,int offset){positive(limit);
     if(offset<0)throw new IllegalArgumentException("offset must not be negative");
 }private static void positive(int n){if(n<1||n>10_000)throw new IllegalArgumentException("limit must be between 1 and 10000");
 }
}
