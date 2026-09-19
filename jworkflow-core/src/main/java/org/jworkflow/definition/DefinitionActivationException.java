package org.jworkflow.definition;

/**
 * Reports a rejected definition activation together with its structured validation result and optional underlying
 * cause.
 */
public final class DefinitionActivationException extends RuntimeException {
    private final transient DefinitionActivationResult result;

    /**
     * Creates a definition activation exception with the supplied diagnostic context.
     * @param result result data associated with the operation
     * @param cause underlying cause, retained for diagnostics
     */
    public DefinitionActivationException(DefinitionActivationResult result, Throwable cause) {
        super("Workflow definition activation rejected for " + result.source().location() + ": "
                + String.join("; ", result.errors()), cause);
        this.result = result;
    }

    /**
     * Returns the structured activation or command result associated with this failure.
     * @return the structured activation or command result associated with this failure
     */
    public DefinitionActivationResult result() { return result; }
}
