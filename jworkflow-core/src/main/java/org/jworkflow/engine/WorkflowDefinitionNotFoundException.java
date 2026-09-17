package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public final class WorkflowDefinitionNotFoundException extends WorkflowCommandException {
    public WorkflowDefinitionNotFoundException(String workflowKey, String workflowVersion) {
        super("definition_not_found", "Unknown workflow definition: " + workflowKey + ":" + workflowVersion);
    }
}
