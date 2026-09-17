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
            try {
                return work.execute();
            } catch (Throwable failure) {
                existing.rollbackOnly = true;
                throw propagate(failure);
            }
        }

        Connection connection = null;
        boolean originalAutoCommit = true;
        boolean originalReadOnly = false;
        int originalIsolation = Connection.TRANSACTION_NONE;
        TransactionState outer = null;
        try {
            connection = connectionFactory.openPhysical();
            originalAutoCommit = connection.getAutoCommit();
            originalReadOnly = connection.isReadOnly();
            originalIsolation = connection.getTransactionIsolation();
            if (immediate) beginImmediate(connection);
            else connection.setAutoCommit(false);
            outer = new TransactionState(connection, immediate);
            state.set(outer);
            connectionFactory.bind(connection, immediate);
            T result = work.execute();
            if (outer.rollbackOnly) {
                rollback(connection, immediate);
                throw new WorkflowPersistenceException("JDBC transaction was marked rollback-only by nested work");
            }
            commit(connection, immediate);
            return result;
        } catch (Throwable failure) {
            if (connection != null && outer != null && !outer.completed) {
                try { rollback(connection, immediate); } catch (Throwable rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            throw propagate(failure);
        } finally {
            state.remove();
            if (connection != null) {
                if (connectionFactory.currentTransactionConnection() == connection) connectionFactory.unbind(connection);
                restoreAndClose(connection, originalAutoCommit, originalReadOnly, originalIsolation);
            }
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
            try { connection.close(); } catch (SQLException ignored) { }
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new WorkflowPersistenceException("JDBC transaction failed", failure);
    }

    private static final class TransactionState {
        private final Connection connection;
        private final boolean immediate;
        private boolean rollbackOnly;
        private boolean completed;
        private TransactionState(Connection connection, boolean immediate) {
            this.connection = connection;
            this.immediate = immediate;
        }
    }
}
