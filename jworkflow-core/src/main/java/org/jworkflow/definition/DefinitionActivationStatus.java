package org.jworkflow.definition;

/**
 * Outcome of compiling, validating and registering a candidate definition.
 */
public enum DefinitionActivationStatus {
    /**
     * A new candidate revision became active.
     */
    ACTIVATED,
    /**
     * The candidate matches the already active revision.
     */
    UNCHANGED,
    /**
     * Validation or execution rejected the requested operation.
     */
    REJECTED
}
