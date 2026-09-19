package org.jworkflow.outbox;

/**
 * A stored outbox message could not be scheduled for manual republishing.
 */
public final class OutboxRepublishException extends RuntimeException{

    /**
     * Creates a outbox republish exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public OutboxRepublishException(String message){super(message);}}
