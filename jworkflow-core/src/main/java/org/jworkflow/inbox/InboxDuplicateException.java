package org.jworkflow.inbox;
public final class InboxDuplicateException extends RuntimeException { public InboxDuplicateException(String source,String external){super("Inbox event already exists: "+source+"/"+external);} }
