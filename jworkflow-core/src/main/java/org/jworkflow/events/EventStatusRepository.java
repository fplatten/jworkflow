package org.jworkflow.events;


import java.util.List;
import java.util.UUID;

public interface EventStatusRepository {
    void append(EventStatusAttempt attempt);

    List<EventStatusAttempt> findAttempts(UUID eventId);
}
