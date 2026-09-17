package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public enum WorkflowStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELED
}
