package org.jworkflow.events;


@FunctionalInterface
public interface EventSubscriberErrorHandler {
    void handle(EventDeliveryFailure failure);

    static EventSubscriberErrorHandler rethrowing() {
        return failure -> {
            if (failure.error() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new EventSubscriberException("Event subscriber failed", failure.error());
        };
    }

    static EventSubscriberErrorHandler ignoring() {
        return failure -> {
        };
    }
}
