package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryEventBus implements EventBus {
    private final ConcurrentMap<String, CopyOnWriteArrayList<SubscriberRegistration>> subscribers = new ConcurrentHashMap<>();
    private final EventSubscriberErrorHandler errorHandler;

    private InMemoryEventBus(EventSubscriberErrorHandler errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public static InMemoryEventBus createDefault() {
        return new InMemoryEventBus(EventSubscriberErrorHandler.ignoring());
    }

    public static InMemoryEventBus create(EventSubscriberErrorHandler errorHandler) {
        return new InMemoryEventBus(errorHandler);
    }

    @Override
    public EventSubscription subscribe(String topic, EventSubscriber subscriber) {
        validateTopic(topic);
        Objects.requireNonNull(subscriber, "subscriber");
        SubscriberRegistration registration = new SubscriberRegistration(
                UUID.randomUUID().toString(),
                subscriber);
        subscribers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(registration);
        return () -> subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>()).remove(registration);
    }

    @Override
    public EventSubscription subscribe(EventListener listener) {
        Objects.requireNonNull(listener, "listener");
        Method onEventMethod = onEventMethod(listener);
        SubscribeTo annotation = onEventMethod.getAnnotation(SubscribeTo.class);
        if (annotation == null || annotation.value().length == 0) {
            throw new IllegalArgumentException("EventListener.onEvent must be annotated with @SubscribeTo");
        }

        List<EventSubscription> subscriptions = new ArrayList<>();
        for (String value : annotation.value()) {
            String topic = new EventName(value).value();
            subscriptions.add(subscribe(topic, listener::onEvent));
        }
        return () -> subscriptions.forEach(EventSubscription::unsubscribe);
    }

    @Override
    public void publish(String topic, WorkflowEvent event) {
        validateTopic(topic);
        Objects.requireNonNull(event, "event");
        for (SubscriberRegistration subscriber : subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
            try {
                subscriber.subscriber().onEvent(event);
            } catch (Exception exception) {
                errorHandler.handle(new EventDeliveryFailure(topic, event, subscriber.id(), exception, Instant.now()));
            }
        }
    }

    private static Method onEventMethod(EventListener listener) {
        try {
            return listener.getClass().getMethod("onEvent", WorkflowEvent.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("EventListener must expose onEvent(WorkflowEvent)", exception);
        }
    }

    private static void validateTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
    }

    private record SubscriberRegistration(String id, EventSubscriber subscriber) {
    }
}
