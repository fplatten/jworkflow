package org.jworkflow.outbox;

/**
 * An outbox message has reached a terminal publication failure.
 */
public final class OutboxDeadLetterException extends RuntimeException{

    /**
     * Creates a outbox dead letter exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public OutboxDeadLetterException(String message){super(message);}}
