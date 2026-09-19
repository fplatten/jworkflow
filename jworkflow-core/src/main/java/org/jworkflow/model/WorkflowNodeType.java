package org.jworkflow.model;


/**
 * Node behavior interpreted by the workflow runtime.
 */
public enum WorkflowNodeType {
    /**
     * An application handler or registered listener step.
     */
    STEP,
    /**
     * A child workflow invocation.
     */
    SUB_WORKFLOW,
    /**
     * An external-event wait with optional timeout.
     */
    WAIT,
    /**
     * A conditional branch node.
     */
    BRANCH,
    /**
     * A gateway selecting outgoing paths.
     */
    GATEWAY,
    /**
     * A node starting named parallel branches.
     */
    FORK,
    /**
     * A node coordinating required branch completions.
     */
    JOIN,
    /**
     * A bounded loop over a body node.
     */
    LOOP,
    /**
     * A terminal graph node.
     */
    END
}
