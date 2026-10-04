package org.jworkflow.workbench;

/** Classified assistant failure with a user-facing message that never contains credentials or request bodies. */
public final class AssistantFailure extends RuntimeException {
    public enum Kind { UNAVAILABLE, AUTHENTICATION, MODEL_UNAVAILABLE, RATE_LIMITED, PROVIDER, MALFORMED, INTERRUPTED, TIMEOUT, CANCELLED }

    private final Kind kind;

    public AssistantFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
