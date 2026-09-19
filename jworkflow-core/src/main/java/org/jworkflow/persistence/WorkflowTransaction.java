package org.jworkflow.persistence;


/**
 * Unit of persistence work without a return value. Implementations throw to abort; transaction managers control
 * commit and rollback.
 */
@FunctionalInterface
public interface WorkflowTransaction {
    /**
     * Performs the unit of work; throwing aborts the manager-owned transaction.
     */
    void execute();
}
