package org.jworkflow.outbox;

/**
 * Durable publication lifecycle, including pending, claimed, published, retry and dead-letter outcomes.
 */
public enum OutboxMessageStatus {
    /**
     * Work is awaiting execution or publication.
     */
    PENDING,
    /**
     * A worker holds a lease for this item.
     */
    CLAIMED,
    /**
     * The transport reported a successful send and it was recorded.
     */
    PUBLISHED,
    /**
     * A failed item is waiting for its next retry deadline.
     */
    RETRY_SCHEDULED,
    /**
     * Automatic retries stopped; manual intervention may requeue the item.
     */
    DEAD_LETTER
}
