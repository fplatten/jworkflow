package org.jworkflow.outbox;

/**
 * Publication could not acquire or validate the required outbox lease.
 */
public final class OutboxClaimException extends RuntimeException{

    /**
     * Creates a outbox claim exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public OutboxClaimException(String message){super(message);}}
