package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public record LoopDefinition(
        BranchCondition whileCondition,
        int maxIterations,
        String stepNode,
        String nextNode
) {
    public LoopDefinition {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
    }
}
