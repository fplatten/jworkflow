package org.jworkflow.definition;

public final class DefinitionActivationException extends RuntimeException {
    private final DefinitionActivationResult result;

    public DefinitionActivationException(DefinitionActivationResult result, Throwable cause) {
        super("Workflow definition activation rejected for " + result.source().location() + ": "
                + String.join("; ", result.errors()), cause);
        this.result = result;
    }

    public DefinitionActivationResult result() { return result; }
}
