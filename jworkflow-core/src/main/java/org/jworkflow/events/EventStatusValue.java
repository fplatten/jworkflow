package org.jworkflow.events;


/**
 * Outcome recorded for one event processing or publication attempt.
 */
public enum EventStatusValue {
    /**
     * Work is awaiting execution or publication.
     */
    PENDING,
    /**
     * The observed attempt succeeded.
     */
    SUCCESSFUL,
    /**
     * Execution or delivery failed.
     */
    FAILED,
    /**
     * Another attempt is in progress or scheduled.
     */
    RETRYING,
    /**
     * The observed work exhausted its retry policy.
     */
    DEAD_LETTERED
}
