package org.jworkflow.events;


public interface EventBus extends EventPublisher {
    EventSubscription subscribe(String topic, EventSubscriber subscriber);

    EventSubscription subscribe(EventListener listener);

    void publish(String topic, WorkflowEvent event);

    @Override
    default void publish(WorkflowEvent event) {
        publish(event.eventName().value(), event);
    }
}
