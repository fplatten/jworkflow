package org.jworkflow.query;
import org.jworkflow.model.WorkflowSnapshot;import java.util.*;
/**
 * Read-only snapshot together with its optional pending wait, pending timers and ordered timeline.
 * @param snapshot point-in-time workflow snapshot
 * @param pendingWait current external-event wait, if any
 * @param pendingTimers timer observations associated with the instance
 * @param timeline ordered workflow history
 */
public record WorkflowDetail(WorkflowSnapshot snapshot,Optional<PendingWaitView> pendingWait,
 List<PendingTimerView> pendingTimers,WorkflowTimeline timeline){

    /**
     * Creates this value from the supplied components.
     * @param snapshot point-in-time workflow snapshot
     * @param pendingWait current external-event wait, if any
     * @param pendingTimers timer observations associated with the instance
     * @param timeline ordered workflow history
     * @throws NullPointerException if pendingWait is null
     */
    public WorkflowDetail{Objects.requireNonNull(pendingWait,"pendingWait");pendingTimers=List.copyOf(pendingTimers);}}
