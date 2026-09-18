package org.jworkflow.events;


public interface EventPublisher {
    void publish(WorkflowEvent event);

    default void publish(IntegrationEvent event) {
        publish(java.util.Objects.requireNonNull(event, "event").toWorkflowEvent());
    }
}
