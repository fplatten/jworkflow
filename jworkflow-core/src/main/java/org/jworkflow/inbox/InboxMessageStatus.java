package org.jworkflow.inbox;

/**
 * Durable inbox lifecycle state, including scheduled retries, active claims and terminal outcomes.
 */
public enum InboxMessageStatus {
    /**
     * An incoming message was durably accepted.
     */
    RECEIVED,
    /**
     * A worker holds a lease for this item.
     */
    CLAIMED,
    /**
     * The incoming message was applied successfully.
     */
    PROCESSED,
    /**
     * A failed item is waiting for its next retry deadline.
     */
    RETRY_SCHEDULED,
    /**
     * Automatic retries stopped; manual intervention may requeue the item.
     */
    DEAD_LETTER
}
