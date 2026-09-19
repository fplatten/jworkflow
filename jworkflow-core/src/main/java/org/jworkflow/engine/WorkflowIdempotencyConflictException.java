package org.jworkflow.engine;


/**
 * A complete idempotency key was reused for a different command type or request content.
 */
public final class WorkflowIdempotencyConflictException extends WorkflowCommandException {
    /**
     * Creates a workflow idempotency conflict exception with the supplied diagnostic context.
     * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
     *     outcome
     */
    public WorkflowIdempotencyConflictException(String idempotencyKey) {
        super("idempotency_conflict", "Idempotency key reused with a different command payload: " + idempotencyKey);
    }
}
