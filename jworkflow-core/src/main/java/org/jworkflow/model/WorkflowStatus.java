package org.jworkflow.model;


/**
 * Lifecycle state of a workflow instance. Node-specific wait semantics are described by the definition and
 * snapshot state, not the status value alone.
 */
public enum WorkflowStatus {
    /**
     * The workflow is actively advancing.
     */
    RUNNING,
    /**
     * The workflow awaits an external event, timer or branch progress.
     */
    WAITING,
    /**
     * The workflow reached successful completion.
     */
    COMPLETED,
    /**
     * Execution or delivery failed.
     */
    FAILED,
    /**
     * The schedule or workflow was explicitly canceled.
     */
    CANCELED
}
