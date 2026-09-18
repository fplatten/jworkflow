package org.jworkflow.persistence;

import org.jworkflow.model.*;

import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionRepository {
    void save(WorkflowDefinition definition);

    Optional<WorkflowDefinition> find(String workflowName, String workflowVersion);

    Optional<WorkflowDefinition> findRevision(String workflowName, String workflowVersion, String workflowRevision);

    List<WorkflowDefinition> findAll();
}
