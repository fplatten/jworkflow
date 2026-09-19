package org.jworkflow.events;


import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Subscriber-error adapter that appends failed delivery status using the supplied repository.
 */
public final class EventStatusRecordingErrorHandler implements EventSubscriberErrorHandler {
    private final EventStatusRepository repository;
    private final ConcurrentMap<String, Integer> attemptsBySubscriberAndEvent = new ConcurrentHashMap<>();

    /**
     * Constructs EventStatusRecordingErrorHandler with the supplied collaborators and configuration.
     * @param repository repository used by this service
     * @throws NullPointerException if repository is null
     */
    public EventStatusRecordingErrorHandler(EventStatusRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handle(EventDeliveryFailure failure) {
        Objects.requireNonNull(failure, "failure");
        int attemptNumber = attemptsBySubscriberAndEvent.merge(key(failure), 1, Integer::sum);
        repository.append(EventStatusAttempt.listenerFailure(
                failure.event(),
                failure.subscriberId(),
                attemptNumber,
                failure.error()));
    }

    private static String key(EventDeliveryFailure failure) {
        return failure.event().metadata().eventId() + ":" + failure.subscriberId();
    }
}
