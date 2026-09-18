package org.jworkflow.engine;

import org.jworkflow.model.*;

public final class WorkflowInstanceNotFoundException extends WorkflowCommandException {
    public WorkflowInstanceNotFoundException(WorkflowInstanceId instanceId) {
        super("instance_not_found", "Unknown workflow instance: " + instanceId);
    }

    public WorkflowInstanceNotFoundException(String message) {
        super("instance_not_found", message);
    }
}
