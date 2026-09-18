package org.jworkflow.jdbc;


import org.sqlite.SQLiteConnection;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
    private final int sqliteBusyTimeoutMillis;
    private final boolean sqliteWalEnabled;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();
    private final ThreadLocal<Boolean> immediateTransaction = new ThreadLocal<>();

    JdbcConnectionFactory(String jdbcUrl, String username, String password, Driver driver, DataSource dataSource) {
        this(jdbcUrl, username, password, driver, dataSource, 5_000, false);
    }

    JdbcConnectionFactory(String jdbcUrl, String username, String password, Driver driver, DataSource dataSource,
                          int sqliteBusyTimeoutMillis, boolean sqliteWalEnabled) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.driver = driver;
        this.dataSource = dataSource;
        if (sqliteBusyTimeoutMillis < 0) throw new IllegalArgumentException("sqliteBusyTimeoutMillis must not be negative");
        this.sqliteBusyTimeoutMillis = sqliteBusyTimeoutMillis;
        this.sqliteWalEnabled = sqliteWalEnabled;
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
            else if (!connectionProperties.isEmpty()) connection = DriverManager.getConnection(jdbcUrl, connectionProperties);
            else connection = DriverManager.getConnection(jdbcUrl);
        }
        if (connection == null) {
            throw new SQLException("Driver did not accept URL: " + jdbcUrl);
        }
        configure(connection);
        return connection;
    }

    void bind(Connection connection, boolean immediate) {
        Objects.requireNonNull(connection, "connection");
        if (transactionConnection.get() != null) throw new IllegalStateException("A JDBC connection is already bound");
        transactionConnection.set(connection);
        immediateTransaction.set(immediate);
    }

    void unbind(Connection expected) {
        if (transactionConnection.get() != expected) throw new IllegalStateException("Unexpected JDBC transaction connection");
        transactionConnection.remove();
        immediateTransaction.remove();
    }

    Connection currentTransactionConnection() { return transactionConnection.get(); }

    void requireImmediateTransaction(String operation) {
        if (transactionConnection.get() == null || !Boolean.TRUE.equals(immediateTransaction.get())) {
            throw new IllegalStateException(operation + " requires JdbcTransactionManager.inImmediateTransaction");
        }
    }

    private void configure(Connection connection) throws SQLException {
        if (!isSqlite(connection)) return;
        connection.unwrap(SQLiteConnection.class).setBusyTimeout(sqliteBusyTimeoutMillis);
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys = on");
            if (sqliteWalEnabled) statement.execute("pragma journal_mode = wal");
        }
    }

    boolean isSqlite(Connection connection) throws SQLException {
        String effectiveUrl = jdbcUrl;
        if (effectiveUrl == null && connection.getMetaData() != null) effectiveUrl = connection.getMetaData().getURL();
        return effectiveUrl != null && effectiveUrl.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:sqlite:");
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
