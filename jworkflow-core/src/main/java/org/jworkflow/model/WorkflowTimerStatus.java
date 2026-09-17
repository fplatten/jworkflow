package org.jworkflow.model;

public enum WorkflowTimerStatus {
    PENDING,
    CLAIMED,
    RETRY_SCHEDULED,
    FIRED,
    CANCELED,
    DEAD_LETTER
}
