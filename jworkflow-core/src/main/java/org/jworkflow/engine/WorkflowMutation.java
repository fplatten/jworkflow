package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowTimer;

import java.util.List;

/** A data-only description of one calculated workflow state transition. */
public record WorkflowMutation(
        WorkflowSnapshot previousSnapshot,
        WorkflowSnapshot nextSnapshot,
        List<WorkflowEvent> events,
        List<WorkflowTimer> timersBefore,
        List<WorkflowTimer> timersAfter
) {
    public WorkflowMutation {
        events = events == null ? List.of() : List.copyOf(events);
        timersBefore = timersBefore == null ? List.of() : List.copyOf(timersBefore);
        timersAfter = timersAfter == null ? List.of() : List.copyOf(timersAfter);
    }
}
