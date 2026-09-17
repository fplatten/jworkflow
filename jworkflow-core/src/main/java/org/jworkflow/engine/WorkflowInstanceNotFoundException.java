package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public final class WorkflowInstanceNotFoundException extends WorkflowCommandException {
    public WorkflowInstanceNotFoundException(WorkflowInstanceId instanceId) {
        super("instance_not_found", "Unknown workflow instance: " + instanceId);
    }

    public WorkflowInstanceNotFoundException(String message) {
        super("instance_not_found", message);
    }
}
