package org.jworkflow.model;


public enum WorkflowNodeType {
    STEP,
    SUB_WORKFLOW,
    WAIT,
    BRANCH,
    GATEWAY,
    FORK,
    JOIN,
    LOOP,
    END
}
