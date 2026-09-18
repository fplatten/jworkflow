package org.jworkflow.events;

/** Indicates that an event subscriber failed while handling a delivered event. */
public final class EventSubscriberException extends RuntimeException {
    public EventSubscriberException(String message, Throwable cause) {
        super(message, cause);
    }
}
