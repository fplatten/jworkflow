package org.jworkflow.engine;

import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSnapshot;

import java.util.List;
import java.util.Optional;

public interface WorkflowEngineContext {
    List<WorkflowSnapshot> getWorkflows();

    Optional<WorkflowSnapshot> getWorkflow(WorkflowInstanceId instanceId);

    Optional<WorkflowSnapshot> getWorkflow(String workflowKey, String businessKey);
}
