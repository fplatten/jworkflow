package org.jworkflow.events;


/**
 * Callback for a delivered workflow event. Implementations must provide their own thread-safety when registered
 * with a concurrently used publisher.
 */
public interface EventListener {
    /**
     * Handles one event delivered by the publisher.
     * @param event event to deliver or inspect
     */
    void onEvent(WorkflowEvent event);
}
