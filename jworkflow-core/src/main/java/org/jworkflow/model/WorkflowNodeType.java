package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
