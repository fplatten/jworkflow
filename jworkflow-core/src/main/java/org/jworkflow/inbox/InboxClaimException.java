package org.jworkflow.inbox;
/**
 * Inbox processing could not acquire or validate the required lease.
 */
public final class InboxClaimException extends RuntimeException {

    /**
     * Creates a inbox claim exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public InboxClaimException(String message){super(message);} }
