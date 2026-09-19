package org.jworkflow.engine;

import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the workflow snapshots visible through an engine. JDBC implementations query persistence;
 * results are observations rather than live mutable engine state.
 */
public interface WorkflowEngineContext {
    /**
     * Returns the snapshots visible to this engine context; this convenience operation is not a bounded pagination
     * API.
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowSnapshot> getWorkflows();

    /**
     * Looks up a snapshot without throwing solely because the instance is absent.
     * @param instanceId workflow instance identity
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowSnapshot> getWorkflow(WorkflowInstanceId instanceId);

    /**
     * Looks up a snapshot without throwing solely because the instance is absent.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowSnapshot> getWorkflow(String workflowKey, String businessKey);
}
