package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public final class WorkflowCommandConflictException extends WorkflowCommandException {
    public WorkflowCommandConflictException(String message) {
        super("command_conflict", message);
    }
}
