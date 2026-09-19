package org.jworkflow.events;

/** Indicates that an event subscriber failed while handling a delivered event. */
public final class EventSubscriberException extends RuntimeException {
    /**
     * Creates a event subscriber exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public EventSubscriberException(String message, Throwable cause) {
        super(message, cause);
    }
}
