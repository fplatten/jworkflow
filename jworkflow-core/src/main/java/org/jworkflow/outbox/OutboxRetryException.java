package org.jworkflow.outbox;

/**
 * A failed publication requires retry handling under the configured attempt policy.
 */
public final class OutboxRetryException extends RuntimeException{

    /**
     * Creates a outbox retry exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public OutboxRetryException(String message,Throwable cause){super(message,cause);}}
