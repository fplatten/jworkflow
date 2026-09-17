package org.jworkflow.persistence;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

@FunctionalInterface
public interface WorkflowTransaction {
    void execute();
}
