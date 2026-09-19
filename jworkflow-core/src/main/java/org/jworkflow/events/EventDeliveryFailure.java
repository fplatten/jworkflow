package org.jworkflow.events;


import java.time.Instant;
import java.util.Objects;

/**
 * Subscriber failure together with the delivered event and subscription identity, passed to an error handler.
 * @param topic event topic used to select subscribers
 * @param event event to deliver or inspect
 * @param subscriberId identity of the subscription receiving the event
 * @param error failure detail to record or report
 * @param failedAt time the failed attempt was recorded
 */
public record EventDeliveryFailure(
        String topic,
        WorkflowEvent event,
        String subscriberId,
        Throwable error,
        Instant failedAt
) {
    /**
     * Creates this value from the supplied components.
     * @param topic event topic used to select subscribers
     * @param event event to deliver or inspect
     * @param subscriberId identity of the subscription receiving the event
     * @param error failure detail to record or report
     * @param failedAt time the failed attempt was recorded
     * @throws NullPointerException if topic, event, subscriberId, error is null
     */
    public EventDeliveryFailure {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(error, "error");
        failedAt = failedAt == null ? Instant.now() : failedAt;
    }
}
