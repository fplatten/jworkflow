package org.jworkflow.events;


/**
 * Boundary for delivering workflow events to an application-provided destination or observer. Implementations
 * define dispatch timing; this interface alone promises neither persistence nor exactly-once delivery.
 */
public interface EventPublisher {
    /**
     * Publishes an event through this implementation's dispatch boundary. The interface does not imply durable
     * delivery or a new workflow instance.
     * @param event event to deliver or inspect
     */
    void publish(WorkflowEvent event);

    /**
     * Publishes an event through this implementation's dispatch boundary. The interface does not imply durable
     * delivery or a new workflow instance.
     * @param event event to deliver or inspect
     * @throws NullPointerException if event is null
     */
    default void publish(IntegrationEvent event) {
        publish(java.util.Objects.requireNonNull(event, "event").toWorkflowEvent());
    }
}
