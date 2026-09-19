package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowTimer;

import java.util.List;

/**
 * A data-only description of one calculated workflow state transition.
 * @param previousSnapshot snapshot before the calculated transition
 * @param nextSnapshot snapshot after the calculated transition
 * @param events workflow events in their supplied order
 * @param timersBefore timers before the calculated transition
 * @param timersAfter timers after the calculated transition
 */
public record WorkflowMutation(
        WorkflowSnapshot previousSnapshot,
        WorkflowSnapshot nextSnapshot,
        List<WorkflowEvent> events,
        List<WorkflowTimer> timersBefore,
        List<WorkflowTimer> timersAfter
) {
    /**
     * Creates this value from the supplied components.
     * @param previousSnapshot snapshot before the calculated transition
     * @param nextSnapshot snapshot after the calculated transition
     * @param events workflow events in their supplied order
     * @param timersBefore timers before the calculated transition
     * @param timersAfter timers after the calculated transition
     */
    public WorkflowMutation {
        events = events == null ? List.of() : List.copyOf(events);
        timersBefore = timersBefore == null ? List.of() : List.copyOf(timersBefore);
        timersAfter = timersAfter == null ? List.of() : List.copyOf(timersAfter);
    }
}
