package org.jworkflow.events;


import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EventStatusRecordingErrorHandler implements EventSubscriberErrorHandler {
    private final EventStatusRepository repository;
    private final ConcurrentMap<String, Integer> attemptsBySubscriberAndEvent = new ConcurrentHashMap<>();

    public EventStatusRecordingErrorHandler(EventStatusRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

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
