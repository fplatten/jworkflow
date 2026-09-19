package org.jworkflow.persistence;


/**
 * Framework-neutral transaction and optional after-commit synchronization boundary. JDBC nested work joins one
 * thread-bound connection and rollback-only state. Custom managers retaining the default synchronization methods
 * dispatch observations immediately; override both capability and dispatch methods for outer-commit timing.
 */
public interface WorkflowTransactionManager {
    /**
     * Runs work under the implementation's atomic commit/rollback boundary.
     * @param transaction unit of work to commit atomically or roll back
     */
    void execute(WorkflowTransaction transaction);

    /**
     * Whether the calling thread is inside this manager's transaction. Custom managers should override.
     * @return true when the condition described above holds; false otherwise
     */
    default boolean isTransactionActive(){return false;}

    /**
     * Whether this manager defers notifications until its outermost commit and resource cleanup.
     * Legacy/custom managers retain immediate delivery unless they override both synchronization methods.
     * In-memory engines have no durable transaction boundary.
     * @return true when outer-transaction synchronization is supported
     */
    default boolean supportsAfterCommit() { return false; }

    /**
     * Runs a best-effort notification, or queues it when a synchronizing manager has an active transaction.
     *  Synchronizing implementations discard queued callbacks on rollback or an unsuccessful commit.
     *  This compatibility default dispatches immediately; it cannot observe custom transaction ownership.
     * @param notification notification to dispatch
     * @throws NullPointerException if notification is null
     */
    // Errors must preserve rollback/resource cleanup or isolate already committed notifications.
    @SuppressWarnings("java:S1181")
    default void afterCommit(Runnable notification) {
        java.util.Objects.requireNonNull(notification, "notification");
        try { notification.run(); }
        catch (Throwable failure) {
            try { reportCompletionFailure(failure); }
            catch (Throwable ignored) { /* Diagnostic hooks cannot change committed outcomes. */ }
        }
    }

    /**
     * Reports a post-commit notification/resource failure without changing the committed result.
     * Custom diagnostic implementations must not throw or expose sensitive exception messages.
     * @param failure original failure, including any suppressed cleanup failures
     */
    default void reportCompletionFailure(Throwable failure) {
        System.getLogger("org.jworkflow.persistence").log(System.Logger.Level.WARNING,
                "Transaction completion notification or resource cleanup failed");
    }

    /**
     * Returns the work result after the manager accepts completion. JDBC implementations join nested work and
     * propagate rollback-only state; this compatibility default delegates to execute.
     * @param <T> the result type
     * @param work work executed inside the transaction boundary
     * @return the work result after the manager accepts completion
     * @throws NullPointerException if work is null
     */
    default <T> T inTransaction(WorkflowTransactionalWork<T> work) {
        java.util.Objects.requireNonNull(work, "work");
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        execute(() -> result.set(work.execute()));
        return result.get();
    }
}
