package org.jworkflow.query;
import org.jworkflow.events.WorkflowEvent;
/**
 * Application projection callback used to consume ordered workflow history. Projection replay does not mutate
 * authoritative workflow state.
 */
@FunctionalInterface public interface WorkflowProjection {

    /**
     * Applies one historical event to an application projection.
     * @param event event to deliver or inspect
     */
    void onEvent(WorkflowEvent event); }
