package org.jworkflow.events;


/**
 * Identifies the processing boundary to which an event-status observation belongs.
 */
public enum EventStatusScope {
    /**
     * A listener invocation.
     */
    LISTENER,
    /**
     * An event publication.
     */
    PUBLISHER,
    /**
     * A durable outgoing publication.
     */
    OUTBOX,
    /**
     * A durable incoming message.
     */
    INBOX,
    /**
     * A repeated delivery or execution attempt.
     */
    RETRY
}
