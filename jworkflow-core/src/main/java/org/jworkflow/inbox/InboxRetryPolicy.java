package org.jworkflow.inbox;


/**
 * Inbox-specific retry budget and deadline policy; the shared backoff contract also serves outbox processing.
 */
public interface InboxRetryPolicy extends org.jworkflow.application.RetryBackoffPolicy { }
