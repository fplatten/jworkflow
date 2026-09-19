package org.jworkflow.persistence;

/** The acquisition is no longer current. Roll back its complete dependent unit of work. */
public final class StaleWorkflowClaimException extends WorkflowPersistenceException {
    /**
     * Creates a stale workflow claim exception with the supplied diagnostic context.
     */
    public StaleWorkflowClaimException(){super("Lease generation is no longer current; dependent work must roll back");}
}
