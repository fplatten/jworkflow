package org.jworkflow.jdbc;

import org.jworkflow.persistence.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-bound, joining transactions. Handlers are never automatically replayed.
 *
 * <p>PostgreSQL adapter transactions use READ COMMITTED; SQLite logical write boundaries use BEGIN IMMEDIATE.
 * Nested work joins the outer transaction and propagates rollback-only state. Borrowed connections must initially
 * be idle and auto-commit enabled. Changed state is restored and connections close before queued callbacks run.
 * Callback failure cannot undo a committed result. Unrelated host/JTA transactions are not enlisted.</p>
 */
public final class JdbcTransactionManager implements WorkflowTransactionManager {
    private final JdbcConnectionFactory connectionFactory;
    private final ThreadLocal<TransactionState> state = new ThreadLocal<>();
    private final ThreadLocal<ArrayDeque<Runnable>> completing = new ThreadLocal<>();
    private final java.util.concurrent.atomic.AtomicReference<Consumer<Throwable>> completionFailureHandler = new java.util.concurrent.atomic.AtomicReference<>();

    JdbcTransactionManager(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    /**
     * Installs an optional diagnostic sink for failures after the durable outcome is known.
     * Replaces the sink atomically; an invocation already in progress may finish using the previous sink.
     * The sink runs synchronously on the reporting thread and may be called concurrently by different
     * transactions, so its implementation must be thread-safe. Sink failures, including Errors, are
     * isolated and fall back to the default generic diagnostic without changing the committed result.
     * @param handler diagnostic callback receiving the original failure; must not expose sensitive details
     * @throws NullPointerException if handler is null
     */
    public void setCompletionFailureHandler(Consumer<Throwable> handler) {
        completionFailureHandler.set(Objects.requireNonNull(handler, "handler"));
    }

    /**
     * {@inheritDoc}
     */
    @Override public boolean supportsAfterCommit() { return true; }
    /**
     * {@inheritDoc}
     */
    @Override public boolean isTransactionActive() { return state.get()!=null; }

    /**
     * {@inheritDoc}
     */
    @Override public void afterCommit(Runnable notification) {
        Objects.requireNonNull(notification, "notification");
        TransactionState current = state.get();
        if (current == null) flush(List.of(notification)); else current.notifications.add(notification);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("java:S1181") // Diagnostic failures, including Errors, cannot undo a known commit.
    @Override public void reportCompletionFailure(Throwable failure) {
        Consumer<Throwable> handler = completionFailureHandler.get();
        if (handler == null) WorkflowTransactionManager.super.reportCompletionFailure(failure);
        else {
            try { handler.accept(failure); }
            catch (Throwable ignored) { WorkflowTransactionManager.super.reportCompletionFailure(failure); }
        }
    }

    @SuppressWarnings("java:S1181") // Isolate each post-commit callback so later notifications still run.
    private void dispatch(Runnable notification) {
        try { notification.run(); } catch (Throwable failure) { reportCompletionFailure(failure); }
    }

    private void flush(List<Runnable> notifications) {
        ArrayDeque<Runnable> pending = completing.get();
        if (pending != null) { pending.addAll(notifications); return; }
        pending = new ArrayDeque<>(notifications);
        completing.set(pending);
        try { while (!pending.isEmpty()) dispatch(pending.removeFirst()); }
        finally { completing.remove(); }
    }

    /**
     * {@inheritDoc}
     */
    @Override public void execute(WorkflowTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        inTransaction(() -> { transaction.execute(); return null; });
    }

    /**
     * {@inheritDoc}
     */
    @Override public <T> T inTransaction(WorkflowTransactionalWork<T> work) {
        return executeInternal(work, false);
    }

    /**
     * Short write/claim boundary: SQLite uses BEGIN IMMEDIATE; PostgreSQL uses READ COMMITTED.
     * Nested work joins its outer boundary; a SQLite deferred outer transaction is not upgraded.
     * @param work database work, without external I/O
     * @return the result of the work
     * @param <T> result type
     */
    public <T> T inWriteTransaction(WorkflowTransactionalWork<T> work) {
        return executeInternal(work, true);
    }

    /**
     * Compatibility alias for {@link #inWriteTransaction(WorkflowTransactionalWork)}.
     * @param <T> the result type
     * @param work work executed inside the transaction boundary
     * @return the value produced by the work
     */
    public <T> T inImmediateTransaction(WorkflowTransactionalWork<T> work) {
        return inWriteTransaction(work);
    }

    @SuppressWarnings("java:S1181") // A nested Error must mark the outer transaction rollback-only.
    private <T> T executeInternal(WorkflowTransactionalWork<T> work, boolean write) {
        Objects.requireNonNull(work, "work");
        TransactionState existing = state.get();
        if (existing == null) return executeOuter(work, write);
        try { return work.execute(); }
        catch (Throwable failure) {
            existing.rollbackOnly = true;
            if (existing.firstFailure == null) existing.firstFailure = failure;
            throw propagate(failure, "work");
        }
    }

    @SuppressWarnings("java:S1181") // Roll back on Error, then rethrow the original Error without replay.
    private <T> T executeOuter(WorkflowTransactionalWork<T> work, boolean write) {
        TransactionResources resources = new TransactionResources();
        TransactionState outer = new TransactionState();
        T result = null;
        try {
            resources.begin(write);
            state.set(outer);
            resources.phase = "work";
            result = work.execute();
            checkRollbackOnly(outer);
            resources.phase = "commit";
            connectionFactory.strategy().beforeCommit(resources.connection);
            if (resources.immediate) boundary(resources.connection, "commit"); else resources.connection.commit();
            resources.committed = true;
            resources.ended = true;
        } catch (Throwable original) {
            resources.failure = original;
            resources.rollback();
        } finally {
            state.remove();
            resources.finish();
        }
        if (resources.failure != null) throw propagate(resources.failure, resources.phase);
        flush(outer.notifications);
        return result;
    }

    private void checkRollbackOnly(TransactionState outer) {
        if (connectionFactory.rollbackCause() != null) throw new WorkflowPersistenceException(
                "JDBC transaction was marked rollback-only by a stale lease guard", connectionFactory.rollbackCause());
        if (outer.rollbackOnly) throw new WorkflowPersistenceException(
                "JDBC transaction was marked rollback-only by nested work", outer.firstFailure);
    }

    /** Owns the borrowed connection and records which cleanup actions are safe. */
    private final class TransactionResources {
        Connection connection;
        boolean originalAuto;
        boolean originalReadOnly;
        boolean captured;
        int originalIsolation;
        boolean immediate;
        boolean started;
        boolean ended;
        boolean committed;
        Throwable failure;
        String phase = "setup";

        void begin(boolean write) throws SQLException {
            connection = connectionFactory.openPhysical();
            originalAuto = connection.getAutoCommit();
            originalReadOnly = connection.isReadOnly();
            originalIsolation = connection.getTransactionIsolation();
            if (!originalAuto) throw new SQLException("Adapter requires an idle auto-commit connection");
            captured = true;
            JdbcDatabaseStrategy strategy = connectionFactory.strategy();
            immediate = write && strategy.usesImmediateWriteTransaction();
            if (originalReadOnly) connection.setReadOnly(false);
            int isolation = strategy.transactionIsolation();
            if (isolation != Connection.TRANSACTION_NONE && isolation != originalIsolation) {
                connection.setTransactionIsolation(isolation);
            }
            started = true;
            if (immediate) boundary(connection, "begin immediate"); else connection.setAutoCommit(false);
            connectionFactory.bind(connection, immediate || !strategy.usesImmediateWriteTransaction());
        }

        @SuppressWarnings("java:S1181") // Preserve the primary failure if rollback itself throws an Error.
        void rollback() {
            if (!started || ended) return;
            try {
                if (immediate) boundary(connection, "rollback"); else connection.rollback();
                ended = true;
            } catch (Throwable rollbackFailure) { suppress(failure, rollbackFailure); }
        }

        void finish() {
            if (connection == null) return;
            if (connectionFactory.currentTransactionConnection() == connection) connectionFactory.unbind(connection);
            // Never enable auto-commit after failed rollback: that could commit partial work.
            Throwable problem = cleanup(connection, captured && (!started || ended),
                    originalAuto, originalReadOnly, originalIsolation);
            if (problem == null) return;
            if (failure != null) suppress(failure, problem);
            else if (committed) reportCompletionFailure(problem);
            else { failure = problem; phase = "cleanup"; }
        }
    }

    @SuppressWarnings("java:S1181") // Attempt every reset/close and retain failures without changing commit outcome.
    private static Throwable cleanup(Connection connection, boolean restore, boolean auto,
            boolean readOnly, int isolation) {
        Throwable failure = null;
        if (restore) {
            try { if (connection.getAutoCommit() != auto) connection.setAutoCommit(auto); }
            catch (Throwable problem) { failure = problem; }
            try { if (connection.isReadOnly() != readOnly) connection.setReadOnly(readOnly); }
            catch (Throwable problem) { failure = collect(failure, problem); }
            try { if (connection.getTransactionIsolation() != isolation) connection.setTransactionIsolation(isolation); }
            catch (Throwable problem) { failure = collect(failure, problem); }
        }
        try { connection.close(); } catch (Throwable problem) { failure = collect(failure, problem); }
        return failure;
    }

    private static Throwable collect(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        suppress(primary, secondary);
        return primary;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) primary.addSuppressed(secondary);
    }

    private static void boundary(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static RuntimeException propagate(Throwable failure, String phase) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new JdbcTransactionException(phase, failure);
    }

    /**
     * Thread-bound rollback-only state and ordered deferred notifications for one outer transaction.
     */
    private static final class TransactionState {
        boolean rollbackOnly;
        Throwable firstFailure;
        final List<Runnable> notifications = new ArrayList<>();
    }
}
