package org.jworkflow.inbox;
/**
 * The requested inbox operation is incompatible with the stored message state.
 */
public final class InboxStateException extends RuntimeException {

    /**
     * Creates a inbox state exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public InboxStateException(String message){super(message);} }
