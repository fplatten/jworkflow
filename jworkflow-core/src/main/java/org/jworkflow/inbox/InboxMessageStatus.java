package org.jworkflow.inbox;

public enum InboxMessageStatus {
    RECEIVED,
    CLAIMED,
    PROCESSED,
    RETRY_SCHEDULED,
    DEAD_LETTER
}
