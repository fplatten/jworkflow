package org.jworkflow.outbox;

public enum OutboxMessageStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    RETRY_SCHEDULED,
    DEAD_LETTER
}
