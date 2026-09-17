package org.jworkflow.query;
import org.jworkflow.model.WorkflowSnapshot;import java.util.*;
public record WorkflowDetail(WorkflowSnapshot snapshot,Optional<PendingWaitView> pendingWait,
 List<PendingTimerView> pendingTimers,WorkflowTimeline timeline){public WorkflowDetail{pendingWait=pendingWait==null?Optional.empty():pendingWait;pendingTimers=List.copyOf(pendingTimers);}}
