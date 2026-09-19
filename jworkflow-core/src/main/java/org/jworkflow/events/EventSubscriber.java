package org.jworkflow.events;


/**
 * Callback for a workflow event delivered by the in-process event bus.
 */
@FunctionalInterface
public interface EventSubscriber {
    /**
     * Handles one workflow event delivered to this subscription.
     * @param event event to deliver or inspect
     */
    void onEvent(WorkflowEvent event);
}
