package org.jworkflow.persistence;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRepository {
    void save(WorkflowDefinition definition);

    Optional<WorkflowDefinition> find(String workflowName, String workflowVersion);

    Optional<WorkflowDefinition> findRevision(String workflowName, String workflowVersion, String workflowRevision);

    List<WorkflowDefinition> findAll();
}
