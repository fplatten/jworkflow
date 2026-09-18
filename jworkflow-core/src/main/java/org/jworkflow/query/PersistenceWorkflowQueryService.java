package org.jworkflow.query;

import org.jworkflow.model.*;
    import org.jworkflow.persistence.*;
import java.time.Instant;
    import java.util.*;

/** Default mutation-free read model backed exclusively by persistence query ports. */
public final class PersistenceWorkflowQueryService implements WorkflowQueryService{
 private final WorkflowPersistence persistence;
 public PersistenceWorkflowQueryService(WorkflowPersistence persistence){this.persistence=Objects.requireNonNull(persistence);
 }
 public List<WorkflowInstanceSummary> list(int limit,int offset){bounds(limit,offset);
     return persistence.instances().findAll(limit,offset).stream().map(WorkflowInstanceSummary::from).toList();
 }
 public Optional<WorkflowDetail> findById(WorkflowInstanceId id){return persistence.instances().findById(Objects.requireNonNull(id)).map(this::detail);
 }
 public Optional<WorkflowDetail> findByBusinessKey(String workflowKey,String businessKey){return persistence.instances().findByBusinessKey(workflowKey,businessKey).map(this::detail);
 }
 public Optional<WorkflowDetail> findByCorrelationId(String correlationId){return persistence.instances().findByCorrelationId(correlationId).map(this::detail);
 }
 public List<WorkflowInstanceSummary> failed(int limit){positive(limit);
     return persistence.instances().findByStatus(WorkflowStatus.FAILED,limit).stream().map(WorkflowInstanceSummary::from).toList();
 }
 public List<WorkflowInstanceSummary> stuck(Instant before,int limit){Objects.requireNonNull(before);
     positive(limit);
     return persistence.instances().findStuck(before,limit).stream().map(WorkflowInstanceSummary::from).toList();
 }
 public WorkflowTimeline timeline(WorkflowInstanceId id){Objects.requireNonNull(id);
     return new WorkflowTimeline(id,persistence.events().findByWorkflowInstance(id).stream().map(WorkflowTimelineEntry::from).toList());
 }
 public List<PendingWaitView> pendingWaits(int limit){positive(limit);
     ArrayList<PendingWaitView> result=new ArrayList<>();
     for(WorkflowSnapshot s:persistence.instances().findActive(limit)){pendingWait(s).ifPresent(result::add);
     if(result.size()==limit)break;
 }
    return List.copyOf(result);
 }
 public List<PendingTimerView> pendingTimers(int limit){positive(limit);
     return persistence.timers().findPending(limit).stream().map(PendingTimerView::from).toList();
 }
 public List<OutboxStateView> pendingOutbox(int limit){positive(limit);
     return persistence.outbox().findPending(limit).stream().map(OutboxStateView::from).toList();
 }
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
