package org.jworkflow.jdbc;


import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.engine.WorkflowInfrastructureException;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.lang.reflect.Proxy;
import java.util.Properties;
import java.util.Objects;
import javax.sql.DataSource;

final class JdbcConnectionFactory {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Driver driver;
    private final DataSource dataSource;
    private final WorkflowEngine.Type expectedType;
    private final Map<String, String> settings;
    private volatile JdbcDatabaseStrategy strategy;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();
    private final ThreadLocal<Boolean> writeTransaction = new ThreadLocal<>();
    private final ThreadLocal<Throwable> rollbackCause = new ThreadLocal<>();

    JdbcConnectionFactory(String jdbcUrl, String username, String password, Driver driver, DataSource dataSource) {
        this(null, jdbcUrl, username, password, driver, dataSource, Map.of());
    }

    JdbcConnectionFactory(String jdbcUrl, String username, String password, Driver driver, DataSource dataSource,
                          int sqliteBusyTimeoutMillis, boolean sqliteWalEnabled) {
        this(null, jdbcUrl, username, password, driver, dataSource, Map.of(
                "sqlite.busy-timeout-ms", Integer.toString(sqliteBusyTimeoutMillis),
                "sqlite.wal-enabled", Boolean.toString(sqliteWalEnabled)));
    }

    JdbcConnectionFactory(WorkflowEngine.Type expectedType, String jdbcUrl, String username, String password,
                          Driver driver, DataSource dataSource, Map<String, String> settings) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.driver = driver;
        this.dataSource = dataSource;
        if (expectedType == WorkflowEngine.Type.IN_MEMORY) throw new IllegalArgumentException("In-memory workflows do not use JDBC");
        if (dataSource == null && (jdbcUrl == null || jdbcUrl.isBlank())) {
            throw new IllegalArgumentException("jdbcUrl or dataSource is required for JDBC persistence");
        }
        this.expectedType = expectedType;
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    Connection open() throws SQLException {
        Connection bound = transactionConnection.get();
        return bound == null ? openPhysical() : closeShield(bound);
    }

    Connection openPhysical() throws SQLException {
        Connection connection;
        if (dataSource != null) {
            connection = dataSource.getConnection();
        } else {
            Properties connectionProperties = new Properties();
            if (username != null) connectionProperties.setProperty("user", username);
            if (password != null) connectionProperties.setProperty("password", password);
            if (driver != null) connection = driver.connect(jdbcUrl, connectionProperties);
            else {
                try {
                    DriverManager.getDriver(jdbcUrl);
                } catch (SQLException missing) {
                    // DriverManager's original message includes the URL, which may contain credentials.
                    throw new SQLException("No JDBC driver accepts the configured URL; install the database driver or supply a Driver/DataSource",
                            missing.getSQLState(), missing.getErrorCode());
                }
                connection = DriverManager.getConnection(jdbcUrl, connectionProperties);
            }
        }
        if (connection == null) {
            throw new SQLException("Supplied JDBC Driver did not accept the configured URL");
        }
        try {
            resolveStrategy(connection).configure(connection);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    void bind(Connection connection, boolean immediate) {
        Objects.requireNonNull(connection, "connection");
        if (transactionConnection.get() != null) throw new IllegalStateException("A JDBC connection is already bound");
        transactionConnection.set(connection);
        writeTransaction.set(immediate);
    }

    void unbind(Connection expected) {
        if (transactionConnection.get() != expected) throw new IllegalStateException("Unexpected JDBC transaction connection");
        transactionConnection.remove();
        writeTransaction.remove();
        rollbackCause.remove();
    }

    Connection currentTransactionConnection() { return transactionConnection.get(); }
    void markRollbackOnly(Throwable failure){if(transactionConnection.get()!=null&&rollbackCause.get()==null)rollbackCause.set(failure);}
    Throwable rollbackCause(){return rollbackCause.get();}

    void requireWriteTransaction(String operation) {
        if (transactionConnection.get() == null || !Boolean.TRUE.equals(writeTransaction.get())) {
            throw new IllegalStateException(operation + " requires JdbcTransactionManager.inWriteTransaction (inImmediateTransaction compatibility alias)");
        }
    }

    void requireImmediateTransaction(String operation) { requireWriteTransaction(operation); }

    private synchronized JdbcDatabaseStrategy resolveStrategy(Connection connection) throws SQLException {
        WorkflowEngine.Type actual = JdbcDatabaseStrategy.productType(connection);
        if ((expectedType != null && expectedType != actual) || (strategy != null && strategy.type() != actual)) {
            throw new SQLException("JDBC database product " + actual + " does not match configured backend "
                    + (expectedType != null ? expectedType : strategy.type()));
        }
        if (strategy == null) strategy = JdbcDatabaseStrategy.forType(actual, settings);
        return strategy;
    }

    JdbcDatabaseStrategy strategy() {
        if (strategy == null) {
            try (Connection ignored = openPhysical()) {
                // Resolve once from real metadata; every later borrow verifies product consistency.
            } catch (SQLException failure) {
                throw new WorkflowInfrastructureException("Failed to validate JDBC database configuration", failure);
            }
        }
        return strategy;
    }

    boolean isSqlite(Connection connection) throws SQLException {
        return resolveStrategy(connection).type() == WorkflowEngine.Type.SQLITE;
    }

    private static Connection closeShield(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    if ("isClosed".equals(method.getName())) return connection.isClosed();
                    if ("unwrap".equals(method.getName()) && ((Class<?>) args[0]).isInstance(connection)) return connection;
                    if ("isWrapperFor".equals(method.getName()) && ((Class<?>) args[0]).isInstance(connection)) return true;
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    Driver driver() {
        return driver;
    }

    DataSource dataSource() {
        return dataSource;
    }
}
