package org.jworkflow.outbox;

/**
 * Base failure in outbox routing, lease acquisition or publication.
 */
public final class OutboxPublicationException extends RuntimeException{

    /**
     * Creates a outbox publication exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public OutboxPublicationException(String message,Throwable cause){super(message,cause);}}
