package org.jworkflow.events;


import java.util.List;
import java.util.UUID;

/**
 * Append-only event-status history port. Callers use the shared transaction manager when status changes must be
 * atomic with workflow or queue updates.
 */
public interface EventStatusRepository {
    /**
     * Appends an immutable delivery or retry observation to event status history.
     * @param attempt immutable attempt history entry
     */
    void append(EventStatusAttempt attempt);

    /**
     * Returns append-only attempt history for the requested message, timer or event identity.
     * @param eventId event identity associated with the message or history row
     * @return the matching values in the order defined by this operation
     */
    List<EventStatusAttempt> findAttempts(UUID eventId);
}
