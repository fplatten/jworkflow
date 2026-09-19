package org.jworkflow.inbox;
/**
 * A stored inbox message could not be scheduled for manual reprocessing.
 */
public final class InboxReprocessingException extends RuntimeException {

    /**
     * Creates a inbox reprocessing exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public InboxReprocessingException(String message){super(message);}

    /**
     * Creates a inbox reprocessing exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     * @param cause underlying cause, retained for diagnostics
     */
    public InboxReprocessingException(String message,Throwable cause){super(message,cause);} }
