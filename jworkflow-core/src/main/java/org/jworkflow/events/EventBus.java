package org.jworkflow.events;


/**
 * In-process event publisher with explicit subscriptions. It is not a durable broker and does not acknowledge
 * external message delivery.
 */
public interface EventBus extends EventPublisher {
    /**
     * Registers a subscriber and returns a handle that removes only that subscription when closed.
     * @param topic event topic used to select subscribers
     * @param subscriber event consumer to register
     * @return a handle for removing the subscription
     */
    EventSubscription subscribe(String topic, EventSubscriber subscriber);

    /**
     * Registers a subscriber and returns a handle that removes only that subscription when closed.
     * @param listener host listener object to register or invoke
     * @return a handle for removing the subscription
     */
    EventSubscription subscribe(EventListener listener);

    /**
     * Delivers to subscriptions matching the supplied topic, or the event name in the convenience overload.
     * @param topic event topic used to select subscribers
     * @param event event to deliver or inspect
     */
    void publish(String topic, WorkflowEvent event);

    /**
     * {@inheritDoc}
     */
    @Override
    default void publish(WorkflowEvent event) {
        publish(event.eventName().value(), event);
    }
}
