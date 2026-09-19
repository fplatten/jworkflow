package org.jworkflow.jdbc;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Harness/tooling checks only; these do not establish PostgreSQL engine support. */
@Timeout(120)
class FoundationPostgresIT {
    private static PostgresTestDatabase database;

    @BeforeAll
    static void startDatabase(TestReporter reporter) throws Exception {
        database = PostgresTestDatabase.start();
        try (Connection connection = database.openAdminConnection()) {
            var metadata = connection.getMetaData();
            Map<String, String> versions = new LinkedHashMap<>();
            versions.put("backend", metadata.getDatabaseProductName());
            versions.put("server", metadata.getDatabaseProductVersion());
            versions.put("driver", metadata.getDriverVersion());
            versions.put("java", System.getProperty("java.version"));
            versions.put("image", database.image());
            versions.put("imageId", database.imageId());
            reporter.publishEntry(versions);
            System.out.println("PostgreSQL foundation environment: " + versions);
            assertEquals("PostgreSQL", metadata.getDatabaseProductName());
        } catch (Exception | Error failure) {
            try {
                database.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            database = null;
            throw failure;
        }
    }

    @AfterAll
    static void removeContainer() throws Exception {
        if (database != null) {
            String containerId = database.containerId();
            database.close();
            assertFalse(database.containerExists(containerId), "test container must be removed");
        }
    }

    @Test
    void independentSessionsAndSchemasPreserveTransactionBoundaries() throws Exception {
        try (var left = database.createSchema(); var right = database.createSchema();
             Connection writer = left.openConnection(); Connection reader = left.openConnection();
             Connection isolated = right.openConnection()) {
            assertNotEquals(scalar(writer, "select pg_backend_pid()"), scalar(reader, "select pg_backend_pid()"));
            assertNotEquals(scalar(writer, "select pg_backend_pid()"), scalar(isolated, "select pg_backend_pid()"));
            try (Statement statement = writer.createStatement()) {
                statement.execute("create table session_probe (id integer primary key)");
            }
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("insert into session_probe values (1)");
            }
            assertEquals(0, scalar(reader, "select count(*) from session_probe"), "uncommitted data is invisible");
            writer.commit();
            assertEquals(1, scalar(reader, "select count(*) from session_probe"), "another session sees commit");
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("insert into session_probe values (2)");
            }
            writer.rollback();
            assertEquals(1, scalar(reader, "select count(*) from session_probe"), "rollback discards the second row");
            assertEquals(0, scalar(isolated,
                    "select count(*) from information_schema.tables where table_schema=current_schema() and table_name='session_probe'"));
            try (Statement statement = isolated.createStatement()) {
                statement.execute("create table session_probe (id integer primary key)");
            }
            assertEquals(0, scalar(isolated, "select count(*) from session_probe"), "same table name is isolated");
        }
    }

    @Test
    void failureCleanupClosesAbandonedConnectionsAndDropsSchema() throws Exception {
        var schema = database.createSchema();
        Connection abandoned = schema.openConnection();
        IllegalStateException injected = assertThrows(IllegalStateException.class, () -> {
            try (schema; Statement statement = abandoned.createStatement()) {
                abandoned.setAutoCommit(false);
                statement.execute("create table abandoned_probe (id integer)");
                throw new IllegalStateException("injected test failure");
            }
        });
        assertEquals("injected test failure", injected.getMessage());
        assertEquals(0, injected.getSuppressed().length, "cleanup itself must succeed");
        assertTrue(abandoned.isClosed());
        try (Connection admin = database.openAdminConnection();
             PreparedStatement statement = admin.prepareStatement("select count(*) from pg_namespace where nspname=?")) {
            statement.setString(1, schema.name());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "failed test schema must be removed");
            }
        }
        schema.close(); // Cleanup is idempotent.
        assertThrows(IllegalStateException.class, schema::openConnection);
    }

    @Test
    void flywayAppliesAndRerunsTestOwnedMigration() throws Exception {
        try (var schema = database.createSchema()) {
            Flyway flyway = Flyway.configure().dataSource(schema.dataSource())
                    .defaultSchema(schema.name()).schemas(schema.name()).createSchemas(false)
                    .locations("classpath:postgres-fixture/migration").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();
            try (Connection connection = schema.openConnection()) {
                assertProbeTypes(connection);
                assertEquals(1, scalar(connection, "select count(*) from flyway_schema_history where success"));
            }
        }
    }

    @Test
    void liquibaseAppliesAndRerunsSameTestOwnedSql() throws Exception {
        try (var schema = database.createSchema(); Connection connection = schema.openConnection()) {
            var liquibaseDatabase = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            liquibaseDatabase.setDefaultSchemaName(schema.name());
            liquibaseDatabase.setLiquibaseSchemaName(schema.name());
            try (Liquibase liquibase = new Liquibase("postgres-fixture/changelog.xml",
                    new ClassLoaderResourceAccessor(), liquibaseDatabase)) {
                liquibase.update(new Contexts());
                liquibase.update(new Contexts());
                assertProbeTypes(connection);
                assertEquals(1, scalar(connection, "select count(*) from databasechangelog"));
            }
        }
    }

    private static void assertProbeTypes(Connection connection) throws Exception {
        byte[] payload = {0, 1, -1, 127};
        BigDecimal instant = new BigDecimal("-0.000000001");
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into tooling_probe (id, payload, instant_value, json_value) values (?, ?, ?, ?)")) {
            statement.setLong(1, 4_294_967_296L);
            statement.setBytes(2, payload);
            statement.setBigDecimal(3, instant);
            statement.setString(4, "{\"label\":\"postgres\"}");
            assertEquals(1, statement.executeUpdate());
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select id,payload,instant_value,json_value from tooling_probe")) {
            assertTrue(result.next());
            assertEquals(4_294_967_296L, result.getLong(1));
            assertArrayEquals(payload, result.getBytes(2));
            assertEquals(0, instant.compareTo(result.getBigDecimal(3)));
            assertEquals("{\"label\":\"postgres\"}", result.getString(4));
            assertFalse(result.next());
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
