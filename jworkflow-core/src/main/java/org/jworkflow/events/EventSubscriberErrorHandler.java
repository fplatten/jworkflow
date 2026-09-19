package org.jworkflow.events;


/**
 * Policy for subscriber delivery failures. Implementations may propagate or discard a failure; neither
 * behavior establishes durable external delivery.
 */
@FunctionalInterface
public interface EventSubscriberErrorHandler {
    /**
     * Handles a failed subscriber delivery according to the configured error policy.
     * @param failure original failure for diagnostics; avoid exposing secrets in logs
     */
    void handle(EventDeliveryFailure failure);

    /**
     * Creates an error handler that rethrows runtime failures unchanged and wraps other failures in
     * {@link EventSubscriberException}.
     * @return the resulting event subscriber error handler
     */
    static EventSubscriberErrorHandler rethrowing() {
        return failure -> {
            if (failure.error() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new EventSubscriberException("Event subscriber failed", failure.error());
        };
    }

    /**
     * Creates an error handler that deliberately discards subscriber failures.
     * @return the resulting event subscriber error handler
     */
    static EventSubscriberErrorHandler ignoring() {
        return failure -> {
        };
    }
}
