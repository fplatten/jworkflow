package org.jworkflow.internal.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.internal.model.WorkflowTimer;

import java.util.List;

/**
 * A data-only description of one calculated workflow state transition.
 * @param previousSnapshot snapshot before the calculated transition
 * @param nextSnapshot snapshot after the calculated transition
 * @param events workflow events in their supplied order
 * @param timersBefore timers before the calculated transition
 * @param timersAfter timers after the calculated transition, limited to the transitioned instance
 * @param createdInstances other instances the transition created and finished, such as sub-workflow children
 */
public record WorkflowMutation(
        WorkflowSnapshot previousSnapshot,
        WorkflowSnapshot nextSnapshot,
        List<WorkflowEvent> events,
        List<WorkflowTimer> timersBefore,
        List<WorkflowTimer> timersAfter,
        List<WorkflowSnapshot> createdInstances
) {
    /**
     * Creates this value from the supplied components.
     * @param previousSnapshot snapshot before the calculated transition
     * @param nextSnapshot snapshot after the calculated transition
     * @param events workflow events in their supplied order
     * @param timersBefore timers before the calculated transition
     * @param timersAfter timers after the calculated transition, limited to the transitioned instance
     * @param createdInstances other instances the transition created and finished, such as sub-workflow children
     */
    public WorkflowMutation {
        events = events == null ? List.of() : List.copyOf(events);
        timersBefore = timersBefore == null ? List.of() : List.copyOf(timersBefore);
        timersAfter = timersAfter == null ? List.of() : List.copyOf(timersAfter);
        createdInstances = createdInstances == null ? List.of() : List.copyOf(createdInstances);
    }

    /**
     * Creates a mutation that created no other instances.
     * @param previousSnapshot snapshot before the calculated transition
     * @param nextSnapshot snapshot after the calculated transition
     * @param events workflow events in their supplied order
     * @param timersBefore timers before the calculated transition
     * @param timersAfter timers after the calculated transition
     */
    public WorkflowMutation(WorkflowSnapshot previousSnapshot, WorkflowSnapshot nextSnapshot, List<WorkflowEvent> events,
                            List<WorkflowTimer> timersBefore, List<WorkflowTimer> timersAfter) {
        this(previousSnapshot, nextSnapshot, events, timersBefore, timersAfter, List.of());
    }
}
