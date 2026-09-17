package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Objects;

public record WorkflowTransition(
        String name,
        String targetNode,
        BranchCondition condition,
        EventName emittedEvent
) {
    public WorkflowTransition {
        Objects.requireNonNull(targetNode, "targetNode");
    }

    public static WorkflowTransition goTo(String targetNode) {
        return new WorkflowTransition(null, targetNode, null, null);
    }
}
