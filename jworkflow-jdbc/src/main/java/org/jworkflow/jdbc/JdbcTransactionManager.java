package org.jworkflow.jdbc;

import org.jworkflow.persistence.WorkflowPersistenceException;
import org.jworkflow.persistence.WorkflowTransaction;
import org.jworkflow.persistence.WorkflowTransactionManager;
import org.jworkflow.persistence.WorkflowTransactionalWork;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Thread-bound JDBC transaction boundary shared by every repository using the same factory. */
public final class JdbcTransactionManager implements WorkflowTransactionManager {
    private final JdbcConnectionFactory connectionFactory;
    private final ThreadLocal<TransactionState> state = new ThreadLocal<>();

    JdbcTransactionManager(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public void execute(WorkflowTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        inTransaction(() -> { transaction.execute(); return null; });
    }

    @Override
    public <T> T inTransaction(WorkflowTransactionalWork<T> work) {
        return executeInternal(work, false);
    }

    /** Short SQLite write/claim boundary. No external I/O may occur inside this callback. */
    public <T> T inImmediateTransaction(WorkflowTransactionalWork<T> work) {
        return executeInternal(work, true);
    }

    private <T> T executeInternal(WorkflowTransactionalWork<T> work, boolean immediate) {
        Objects.requireNonNull(work, "work");
        TransactionState existing = state.get();
        if (existing != null) {
            return executeNested(work, existing);
        }
        return executeOuter(work, immediate);
    }

    private <T> T executeOuter(WorkflowTransactionalWork<T> work, boolean immediate) {
        Connection connection = null;
        boolean originalAutoCommit = true;
        boolean originalReadOnly = false;
        int originalIsolation = Connection.TRANSACTION_NONE;
        TransactionState outer = null;
        boolean effectiveImmediate = false;
        try {
            connection = connectionFactory.openPhysical();
            originalAutoCommit = connection.getAutoCommit();
            originalReadOnly = connection.isReadOnly();
            originalIsolation = connection.getTransactionIsolation();
            effectiveImmediate = immediate && connectionFactory.isSqlite(connection);
            if (effectiveImmediate) beginImmediate(connection);
            else connection.setAutoCommit(false);
            outer = new TransactionState();
            state.set(outer);
            connectionFactory.bind(connection, effectiveImmediate);
            T result = work.execute();
            if (outer.rollbackOnly) {
                rollback(connection, effectiveImmediate);
                throw new WorkflowPersistenceException("JDBC transaction was marked rollback-only by nested work");
            }
            commit(connection, effectiveImmediate);
            return result;
        } catch (Exception failure) {
            rollbackAfterFailure(connection, outer, effectiveImmediate, failure);
            throw propagate(failure);
        } finally {
            cleanup(connection, outer, effectiveImmediate, originalAutoCommit, originalReadOnly, originalIsolation);
        }
    }

    private void rollbackAfterFailure(
            Connection connection, TransactionState outer, boolean immediate, Exception failure) {
        if (connection != null && outer != null && !outer.completed) {
            try {
                rollback(connection, immediate);
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void cleanup(Connection connection, TransactionState outer, boolean immediate,
            boolean autoCommit, boolean readOnly, int isolation) {
        state.remove();
        if (connection == null) return;
        if (outer != null && !outer.completed) rollbackQuietly(connection, immediate);
        if (connectionFactory.currentTransactionConnection() == connection) connectionFactory.unbind(connection);
        restoreAndClose(connection, autoCommit, readOnly, isolation);
    }

    private static <T> T executeNested(WorkflowTransactionalWork<T> work, TransactionState existing) {
        try {
            return work.execute();
        } catch (Exception failure) {
            existing.rollbackOnly = true;
            throw propagate(failure);
        }
    }

    private void commit(Connection connection, boolean immediate) throws SQLException {
        if (immediate) executeBoundary(connection, "commit"); else connection.commit();
        state.get().completed = true;
    }

    private void rollback(Connection connection, boolean immediate) throws SQLException {
        if (immediate) executeBoundary(connection, "rollback"); else connection.rollback();
        TransactionState current = state.get();
        if (current != null) current.completed = true;
    }

    private static void beginImmediate(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) connection.setAutoCommit(true);
        executeBoundary(connection, "begin immediate");
    }

    private static void executeBoundary(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void restoreAndClose(Connection connection, boolean autoCommit, boolean readOnly, int isolation) {
        try {
            if (!connection.isClosed()) {
                if (connection.isReadOnly() != readOnly) connection.setReadOnly(readOnly);
                if (isolation != Connection.TRANSACTION_NONE && connection.getTransactionIsolation() != isolation) {
                    connection.setTransactionIsolation(isolation);
                }
                if (connection.getAutoCommit() != autoCommit) connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ignored) {
            // The primary transaction outcome is more useful; pools discard broken connections on close.
        } finally {
            try { connection.close(); } catch (SQLException ignored) {
                // Closing is best-effort after the transaction has already completed.
            }
        }
    }

    private void rollbackQuietly(Connection connection, boolean immediate) {
        try {
            rollback(connection, immediate);
        } catch (SQLException ignored) {
            // Preserve the original Error while still attempting a best-effort rollback.
        }
    }

    private static RuntimeException propagate(Exception failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        return new WorkflowPersistenceException("JDBC transaction failed", failure);
    }

    private static final class TransactionState {
        private boolean rollbackOnly;
        private boolean completed;
    }
}
