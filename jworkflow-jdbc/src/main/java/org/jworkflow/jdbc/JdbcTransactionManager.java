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

/** Thread-bound, joining transactions. Handlers are never automatically replayed. */
public final class JdbcTransactionManager implements WorkflowTransactionManager {
    private final JdbcConnectionFactory connectionFactory;
    private final ThreadLocal<TransactionState> state = new ThreadLocal<>();
    private final ThreadLocal<ArrayDeque<Runnable>> completing = new ThreadLocal<>();
    private volatile Consumer<Throwable> completionFailureHandler;

    JdbcTransactionManager(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    /** Installs an optional diagnostic sink for failures after the durable outcome is known. */
    public void setCompletionFailureHandler(Consumer<Throwable> handler) {
        completionFailureHandler = Objects.requireNonNull(handler, "handler");
    }

    @Override public boolean supportsAfterCommit() { return true; }
    @Override public boolean isTransactionActive() { return state.get()!=null; }

    @Override public void afterCommit(Runnable notification) {
        Objects.requireNonNull(notification, "notification");
        TransactionState current = state.get();
        if (current == null) flush(List.of(notification)); else current.notifications.add(notification);
    }

    @Override public void reportCompletionFailure(Throwable failure) {
        Consumer<Throwable> handler = completionFailureHandler;
        if (handler == null) WorkflowTransactionManager.super.reportCompletionFailure(failure);
        else {
            try { handler.accept(failure); }
            catch (Throwable ignored) { WorkflowTransactionManager.super.reportCompletionFailure(failure); }
        }
    }

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

    @Override public void execute(WorkflowTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        inTransaction(() -> { transaction.execute(); return null; });
    }

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

    /** Compatibility alias for {@link #inWriteTransaction(WorkflowTransactionalWork)}. */
    public <T> T inImmediateTransaction(WorkflowTransactionalWork<T> work) {
        return inWriteTransaction(work);
    }

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

    private <T> T executeOuter(WorkflowTransactionalWork<T> work, boolean write) {
        Connection connection = null;
        boolean originalAuto = true, originalReadOnly = false, captured = false;
        int originalIsolation = Connection.TRANSACTION_NONE;
        boolean immediate = false, started = false, ended = false, committed = false;
        Throwable failure = null;
        String phase = "setup";
        T result = null;
        TransactionState outer = new TransactionState();
        try {
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
            if (isolation != Connection.TRANSACTION_NONE && isolation != originalIsolation)
                connection.setTransactionIsolation(isolation);
            started = true;
            if (immediate) boundary(connection, "begin immediate"); else connection.setAutoCommit(false);
            state.set(outer);
            connectionFactory.bind(connection, immediate || !strategy.usesImmediateWriteTransaction());
            phase = "work";
            result = work.execute();
            if(connectionFactory.rollbackCause()!=null)throw new WorkflowPersistenceException(
                    "JDBC transaction was marked rollback-only by a stale lease guard",connectionFactory.rollbackCause());
            if (outer.rollbackOnly) throw new WorkflowPersistenceException(
                    "JDBC transaction was marked rollback-only by nested work", outer.firstFailure);
            phase = "commit";
            strategy.beforeCommit(connection);
            if (immediate) boundary(connection, "commit"); else connection.commit();
            committed = true;
            ended = true;
        } catch (Throwable original) {
            failure = original;
            if (started && !ended) {
                try {
                    if (immediate) boundary(connection, "rollback"); else connection.rollback();
                    ended = true;
                } catch (Throwable rollbackFailure) { suppress(failure, rollbackFailure); }
            }
        } finally {
            state.remove();
            if (connection != null) {
                if (connectionFactory.currentTransactionConnection() == connection) connectionFactory.unbind(connection);
                // Never enable auto-commit after failed rollback: that could commit partial work.
                Throwable cleanup = cleanup(connection, captured && (!started || ended),
                        originalAuto, originalReadOnly, originalIsolation);
                if (cleanup != null) {
                    if (failure != null) suppress(failure, cleanup);
                    else if (committed) reportCompletionFailure(cleanup);
                    else { failure = cleanup; phase = "cleanup"; }
                }
            }
        }
        if (failure != null) throw propagate(failure, phase);
        flush(outer.notifications);
        return result;
    }

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

    private static final class TransactionState {
        boolean rollbackOnly;
        Throwable firstFailure;
        final List<Runnable> notifications = new ArrayList<>();
    }
}
