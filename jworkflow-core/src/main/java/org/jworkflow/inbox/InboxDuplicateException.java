package org.jworkflow.inbox;
/**
 * An inbox identity conflicts with an existing stored message.
 */
public final class InboxDuplicateException extends RuntimeException {

    /**
     * Creates a inbox duplicate exception with the supplied diagnostic context.
     * @param source external source system used for inbox deduplication
     * @param external external event identity within its source
     */
    public InboxDuplicateException(String source,String external){super("Inbox event already exists: "+source+"/"+external);} }
