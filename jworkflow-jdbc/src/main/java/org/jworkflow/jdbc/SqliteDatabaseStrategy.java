package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowEngine;
import org.sqlite.SQLiteConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/** Loaded only for SQLite; all SQLite driver linkage and PRAGMAs live here. */
final class SqliteDatabaseStrategy implements JdbcDatabaseStrategy {
    @Override public boolean usesImmediateWriteTransaction() { return true; }
    @Override public int transactionIsolation() { return Connection.TRANSACTION_NONE; }
    private final int busyTimeout;
    private final boolean wal;

    SqliteDatabaseStrategy(Map<String, String> settings) {
        busyTimeout = Integer.parseInt(settings.getOrDefault("sqlite.busy-timeout-ms", "5000"));
        if (busyTimeout < 0 || busyTimeout > 600_000) {
            throw new IllegalArgumentException("SQLite busy timeout must be between 0 and 600000 ms");
        }
        wal = Boolean.parseBoolean(settings.getOrDefault("sqlite.wal-enabled", "false"));
    }

    static void loadDriver() throws ClassNotFoundException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new ClassNotFoundException("SQLITE requires org.xerial:sqlite-jdbc or a supplied Driver/DataSource", failure);
        }
    }

    @Override public WorkflowEngine.Type type() { return WorkflowEngine.Type.SQLITE; }

    @Override public void configure(Connection connection) throws SQLException {
        connection.unwrap(SQLiteConnection.class).setBusyTimeout(busyTimeout);
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys = on");
            if (wal) statement.execute("pragma journal_mode = wal");
        }
    }

    @Override public void initializeSchema(JdbcConnectionFactory factory) {
        JdbcSchemaInitializer.initializeSqlite(factory);
    }
}
