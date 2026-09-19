package org.jworkflow.persistence;

import org.jworkflow.model.*;

import java.util.List;
import java.util.Optional;

/**
 * Persists immutable definition revisions and retrieves the exact revision required by a durable snapshot. Reusing
 * an identity with conflicting immutable content must fail.
 */
public interface WorkflowDefinitionRepository {
    /**
     * Persists immutable definition content; matching existing revisions are retained and conflicting content
     * fails.
     * @param definition immutable workflow definition
     */
    void save(WorkflowDefinition definition);

    /**
     * Finds a stored definition by workflow name and version.
     * @param workflowName workflow definition name
     * @param workflowVersion workflow definition version
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowDefinition> find(String workflowName, String workflowVersion);

    /**
     * Loads the exact persisted revision, not merely the selected same-name/version definition.
     * @param workflowName workflow definition name
     * @param workflowVersion workflow definition version
     * @param workflowRevision exact immutable definition revision pinned by the workflow
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowDefinition> findRevision(String workflowName, String workflowVersion, String workflowRevision);

    /**
     * Loads all stored definitions; this is not a bounded revision-history query.
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowDefinition> findAll();
}
