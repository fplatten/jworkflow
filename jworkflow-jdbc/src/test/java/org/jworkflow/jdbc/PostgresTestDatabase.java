package org.jworkflow.jdbc;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test-owned PostgreSQL server with isolated schemas and fresh physical connections.
 * No application migrations or engine implementation are required.
 */
final class PostgresTestDatabase implements AutoCloseable {
    private final PostgreSQLContainer container;
    private final List<Schema> schemas = new ArrayList<>();

    private PostgresTestDatabase(PostgreSQLContainer container) {
        this.container = container;
    }

    static PostgresTestDatabase start() {
        return start(false);
    }

    static PostgresTestDatabase start(boolean operationalLimits) {
        PostgreSQLContainer container = new PostgreSQLContainer(
                System.getProperty("postgres.image",
                        "postgres@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675"))
                .withDatabaseName("jworkflow_test")
                .withUsername("jworkflow_test")
                .withPassword(UUID.randomUUID().toString())
                .withCommand("postgres", "-c", "fsync=on")
                .withStartupTimeout(Duration.ofSeconds(60))
                .withReuse(false);
        if (operationalLimits) container.withCreateContainerCmdModifier(command ->
                command.getHostConfig().withMemory(1024L * 1024 * 1024).withNanoCPUs(2_000_000_000L));
        try {
            container.start(); // Deliberately fail, never skip, if Docker/startup is unavailable.
            return new PostgresTestDatabase(container);
        } catch (RuntimeException | Error failure) {
            try {
                container.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    Connection openAdminConnection() throws SQLException {
        return source(null).getConnection();
    }

    synchronized Schema createSchema() throws SQLException {
        // Only internally generated identifiers enter DDL; never a host-provided schema.
        String name = "it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openAdminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create schema " + name);
        }
        Schema schema = new Schema(name, source(name));
        schemas.add(schema);
        return schema;
    }

    private PGSimpleDataSource source(String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(container.getJdbcUrl());
        source.setUser(container.getUsername());
        source.setPassword(container.getPassword());
        source.setConnectTimeout(10);
        source.setSocketTimeout(30);
        source.setOptions("-c statement_timeout=10000 -c lock_timeout=5000");
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    String image() {
        return container.getDockerImageName();
    }

    String imageId() {
        return container.getContainerInfo().getImageId();
    }

    String containerId() {
        return container.getContainerId();
    }

    boolean containerExists(String id) {
        try {
            container.getDockerClient().inspectContainerCmd(id).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException expected) {
            return false;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        SQLException failure = null;
        try {
            for (Schema schema : schemas) {
                try {
                    schema.close();
                } catch (SQLException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        } finally {
            try {
                container.close();
            } catch (RuntimeException exception) {
                if (failure == null) throw exception;
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    final class Schema implements AutoCloseable {
        private final String name;
        private final PGSimpleDataSource source;
        private final List<Connection> connections = new ArrayList<>();
        private boolean closed;

        private Schema(String name, PGSimpleDataSource source) {
            this.name = name;
            this.source = source;
        }

        String name() {
            return name;
        }

        DataSource dataSource() {
            if (closed) throw new IllegalStateException("Schema is closed");
            return source;
        }

        /** A fresh physical session, owned by this schema until caller/fixture close. */
        synchronized Connection openConnection() throws SQLException {
            if (closed) throw new IllegalStateException("Schema is closed");
            Connection connection = source.getConnection();
            connections.add(connection);
            return connection;
        }

        @Override
        public synchronized void close() throws SQLException {
            if (closed) return;
            SQLException failure = null;
            for (Connection connection : connections) {
                try {
                    connection.close(); // Also rolls back an abandoned transaction.
                } catch (SQLException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            try (Connection connection = openAdminConnection(); Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + name + " cascade");
                closed = true;
            } catch (SQLException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        }
    }
}
