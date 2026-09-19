package org.jworkflow.observability;

/**
 * Lifecycle boundary represented by a workflow observation.
 */
public enum WorkflowLifecycleEventType {
    /**
     * An instance started.
     */
    WORKFLOW_STARTED,
    /**
     * An instance completed successfully.
     */
    WORKFLOW_COMPLETED,
    /**
     * An instance entered failure.
     */
    WORKFLOW_FAILED,
    /**
     * Execution entered a step.
     */
    STEP_ENTERED,
    /**
     * A step completed successfully.
     */
    STEP_COMPLETED,
    /**
     * A step failed.
     */
    STEP_FAILED,
    /**
     * Execution selected an outgoing transition.
     */
    TRANSITION_TAKEN,
    /**
     * An event reached the engine.
     */
    EVENT_RECEIVED,
    /**
     * An event was matched to workflow state.
     */
    EVENT_CORRELATED,
    /**
     * An event did not advance workflow state.
     */
    EVENT_IGNORED,
    /**
     * A failed item is waiting for its next retry deadline.
     */
    RETRY_SCHEDULED,
    /**
     * A due timer was applied.
     */
    TIMER_FIRED,
    /**
     * Publication intent was stored.
     */
    OUTBOX_CREATED,
    /**
     * A publication succeeded.
     */
    OUTBOX_PUBLISHED,
    /**
     * A publication attempt failed.
     */
    OUTBOX_PUBLICATION_FAILED,
    /**
     * An incoming message exhausted automatic retries.
     */
    INBOX_DEAD_LETTERED
}
