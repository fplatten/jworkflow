package org.jworkflow.persistence;

/** The acquisition is no longer current. Roll back its complete dependent unit of work. */
public final class StaleWorkflowClaimException extends WorkflowPersistenceException {
    public StaleWorkflowClaimException(){super("Lease generation is no longer current; dependent work must roll back");}
}
