package org.jworkflow.jdbc;

/**
 * Logs background worker failures. Only exception types are recorded, because driver and handler messages can
 * contain payloads, SQL or credentials.
 */
final class JdbcWorkerDiagnostics {
    private static final System.Logger LOGGER = System.getLogger("org.jworkflow.jdbc");

    private JdbcWorkerDiagnostics() { }

    /**
     * Reports a failed scheduled poll; the worker keeps its schedule and retries on the next poll.
     * @param worker worker kind, such as {@code timer}
     * @param failure poll failure
     */
    static void pollFailed(String worker, Throwable failure) {
        LOGGER.log(System.Logger.Level.WARNING, "jworkflow {0} poll failed with {1}; the next poll retries",
                worker, failure.getClass().getName());
    }

    /**
     * Reports one failed item in a claimed batch; the rest of the batch is still processed.
     * @param worker worker kind, such as {@code outbox}
     * @param failure item failure
     */
    static void itemFailed(String worker, Throwable failure) {
        LOGGER.log(System.Logger.Level.WARNING, "jworkflow {0} item failed with {1}; continuing with the batch",
                worker, failure.getClass().getName());
    }

    /**
     * Reports that a worker stopped because a transaction outcome is unknown and needs operator reconciliation.
     * @param worker worker kind, such as {@code inbox}
     * @param failure failure whose outcome could not be recorded
     */
    static void paused(String worker, Throwable failure) {
        LOGGER.log(System.Logger.Level.ERROR, "jworkflow {0} polling paused after {1}: reconcile the failed "
                + "transaction, then recreate the worker", worker, failure.getClass().getName());
    }
}
