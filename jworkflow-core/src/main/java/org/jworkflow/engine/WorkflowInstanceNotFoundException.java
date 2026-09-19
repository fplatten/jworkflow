package org.jworkflow.engine;

import org.jworkflow.model.*;

/**
 * The requested workflow instance could not be found.
 */
public final class WorkflowInstanceNotFoundException extends WorkflowCommandException {
    /**
     * Creates a workflow instance not found exception with the supplied diagnostic context.
     * @param instanceId workflow instance identity
     */
    public WorkflowInstanceNotFoundException(WorkflowInstanceId instanceId) {
        super("instance_not_found", "Unknown workflow instance: " + instanceId);
    }

    /**
     * Creates a workflow instance not found exception with the supplied diagnostic context.
     * @param message human-readable diagnostic detail
     */
    public WorkflowInstanceNotFoundException(String message) {
        super("instance_not_found", message);
    }
}
