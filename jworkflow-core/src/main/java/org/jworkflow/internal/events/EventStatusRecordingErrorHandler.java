package org.jworkflow.internal.events;


import java.util.Objects;

/**
 * Subscriber-error adapter that appends failed delivery status using the supplied repository.
 */
public final class EventStatusRecordingErrorHandler implements EventSubscriberErrorHandler {
    private final EventStatusRepository repository;

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
        // The next attempt number comes from recorded history rather than an in-memory map that grew with every
        // failed event. Serialized so concurrent failures for one event cannot pick the same number.
        synchronized (this) {
            int attemptNumber = 1 + repository.findAttempts(failure.event().metadata().eventId()).stream()
                    .filter(attempt -> attempt.scope() == EventStatusScope.LISTENER
                            && Objects.equals(attempt.handlerId(), failure.subscriberId()))
                    .mapToInt(EventStatusAttempt::attemptNumber).max().orElse(0);
            repository.append(EventStatusAttempt.listenerFailure(
                    failure.event(),
                    failure.subscriberId(),
                    attemptNumber,
                    failure.error()));
        }
    }
}
