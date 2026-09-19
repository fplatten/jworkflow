package org.jworkflow.engine;


/**
 * Command outcome: accepted, no state change, rejected input/state, or failed execution.
 */
public enum WorkflowCommandStatus {
    /**
     * The command was accepted and its result applied.
     */
    ACCEPTED,
    /**
     * The command required no state change.
     */
    NO_OP,
    /**
     * Validation or execution rejected the requested operation.
     */
    REJECTED,
    /**
     * Execution or delivery failed.
     */
    FAILED
}
