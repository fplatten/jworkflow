package org.jworkflow.engine;


public final class WorkflowIdempotencyConflictException extends WorkflowCommandException {
    public WorkflowIdempotencyConflictException(String idempotencyKey) {
        super("idempotency_conflict", "Idempotency key reused with a different command payload: " + idempotencyKey);
    }
}
