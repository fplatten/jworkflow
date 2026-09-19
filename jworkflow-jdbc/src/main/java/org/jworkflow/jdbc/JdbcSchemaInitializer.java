package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Applies the same ordered SQL resources exposed to Flyway and Liquibase. */
final class JdbcSchemaInitializer {
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "create jworkflow schema", "db/migration/V1__create_jworkflow_schema.sql"),
            new Migration(2, "durable workflow and messaging", "db/migration/V2__durable_workflow_and_messaging.sql"),
            new Migration(3, "timer attempt history", "db/migration/V3__timer_attempt_history.sql"),
            new Migration(4, "event routing indexes", "db/migration/V4__event_routing_indexes.sql"),
            new Migration(5, "message redaction status", "db/migration/V5__message_redaction_status.sql"),
            new Migration(6, "lease generation fencing", "db/migration/V6__lease_generation_fencing.sql"));

    private JdbcSchemaInitializer() { }

    static void initialize(JdbcConnectionFactory connectionFactory) {
        connectionFactory.strategy().initializeSchema(connectionFactory);
    }

    static void initializeSqlite(JdbcConnectionFactory connectionFactory) {
        try (Connection connection = connectionFactory.openPhysical()) {
            applyMigrations(connection);
        } catch (Exception exception) {
            throw new WorkflowInfrastructureException("Failed to initialize jworkflow schema", exception);
        }
    }

    private static void applyMigrations(Connection connection)
            throws SQLException, IOException, NoSuchAlgorithmException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            createHistory(connection);
            for (Migration migration : MIGRATIONS) {
                applyIfNeeded(connection, migration);
            }
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void createHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists jworkflow_schema_history (
                        version integer primary key,
                        description varchar(255) not null,
                        checksum varchar(64) not null,
                        installed_at varchar(64) not null
                    )
                    """);
        }
    }

    private static void applyIfNeeded(Connection connection, Migration migration)
            throws SQLException, IOException, NoSuchAlgorithmException {
        String sql = read(migration.resource());
        String checksum = sha256(sql);
        if (alreadyApplied(connection, migration, checksum)) {
            return;
        }
        for (String command : statements(sql)) {
            try (Statement statement = connection.createStatement()) { statement.execute(command); }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into jworkflow_schema_history (version, description, checksum, installed_at) values (?, ?, ?, ?)")) {
            insert.setInt(1, migration.version());
            insert.setString(2, migration.description());
            insert.setString(3, checksum);
            insert.setString(4, Instant.now().toString());
            insert.executeUpdate();
        }
    }

    private static boolean alreadyApplied(Connection connection, Migration migration, String checksum)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select checksum from jworkflow_schema_history where version = ?")) {
            query.setInt(1, migration.version());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                if (!checksum.equals(rows.getString(1))) {
                    throw new SQLException("Applied schema migration V" + migration.version() + " checksum differs");
                }
                return true;
            }
        }
    }

    static List<String> statements(String sql) {
        String withoutComments = sql.replaceAll("(?m)^\\s*--.*$", "");
        return java.util.Arrays.stream(withoutComments.split(";"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    static String read(String resource) throws IOException {
        try (InputStream input = JdbcSchemaInitializer.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing schema resource: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Migration(int version, String description, String resource) { }
}
