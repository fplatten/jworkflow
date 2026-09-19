package org.jworkflow.model;

/**
 * Durable timer lifecycle state for pending, claimed, completed, retry and terminal processing.
 */
public enum WorkflowTimerStatus {
    /**
     * Work is awaiting execution or publication.
     */
    PENDING,
    /**
     * A worker holds a lease for this item.
     */
    CLAIMED,
    /**
     * A failed item is waiting for its next retry deadline.
     */
    RETRY_SCHEDULED,
    /**
     * The timer effects were applied successfully.
     */
    FIRED,
    /**
     * The schedule or workflow was explicitly canceled.
     */
    CANCELED,
    /**
     * Automatic retries stopped; manual intervention may requeue the item.
     */
    DEAD_LETTER
}
