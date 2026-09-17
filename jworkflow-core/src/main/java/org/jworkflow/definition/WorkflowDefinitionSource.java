package org.jworkflow.definition;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.List;

public interface WorkflowDefinitionSource {
    List<WorkflowDefinitionText> load();
}
