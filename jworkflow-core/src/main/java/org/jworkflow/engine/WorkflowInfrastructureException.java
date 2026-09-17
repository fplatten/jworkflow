package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public final class WorkflowInfrastructureException extends WorkflowCommandException {
    public WorkflowInfrastructureException(String message, Throwable cause) {
        super("infrastructure_failure", message, cause);
    }
}
