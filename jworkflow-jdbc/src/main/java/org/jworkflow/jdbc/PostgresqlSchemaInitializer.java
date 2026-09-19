package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns one schema migration transaction; never adopts deployment-tool histories. */
final class PostgresqlSchemaInitializer {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "PostgreSQL baseline",
            "db/postgresql/migration/V1__postgresql_baseline.sql"),
            new Migration(2, "Active instance keyset index", "db/postgresql/migration/V2__active_instance_keyset_index.sql"));
    // A separate advisory-lock namespace from future command idempotency locks.
    static final int LOCK_NAMESPACE = 0x4a57534d;

    private PostgresqlSchemaInitializer() { }

    static void initialize(JdbcConnectionFactory factory) { initialize(factory, MIGRATIONS); }

    static void initialize(JdbcConnectionFactory factory, List<Migration> migrations) {
        try (Connection connection = factory.openPhysical()) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("Built-in PostgreSQL initialization requires an auto-commit connection free of host work");
            }
            migrate(connection, migrations);
        } catch (Exception failure) {
            throw new WorkflowInfrastructureException("Failed to initialize PostgreSQL jworkflow schema", failure);
        }
    }

    // Migration failure must roll back even when application or driver code throws an Error.
    @SuppressWarnings("java:S1181")
    private static void migrate(Connection connection, List<Migration> migrations)
            throws SQLException, java.io.IOException, java.security.NoSuchAlgorithmException {
            boolean readOnly = connection.isReadOnly();
            int isolation = connection.getTransactionIsolation();
            Throwable primary = null;
            boolean transactionEnded = false;
            try {
                connection.setReadOnly(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                lockSchema(connection);
                verifyOwnership(connection);
                execute(connection, """
                        create table if not exists jworkflow_schema_history (
                            version integer primary key, description varchar(255) not null,
                            checksum varchar(64) not null, installed_at numeric(30,9) not null
                        )
                        """);
                verifyKnownVersions(connection, migrations);
                for (Migration migration : migrations) apply(connection, migration);
                connection.commit();
                transactionEnded = true;
            } catch (Exception | Error failure) {
                primary = failure;
                transactionEnded = rollback(connection, failure);
                throw failure;
            } finally {
                // Enabling auto-commit after a failed rollback could commit uncertain work.
                // Close the unusable borrow without further state changes in that case.
                if (transactionEnded) restore(connection, readOnly, isolation, primary);
            }
    }

    private static boolean rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); return true; }
        catch (SQLException rollback) { failure.addSuppressed(rollback); return false; }
    }

    private static void lockSchema(Connection connection) throws SQLException {
        String schema;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("select current_schema()")) {
            rows.next();
            schema = rows.getString(1);
            if (schema == null) throw new SQLException("PostgreSQL initialization requires an existing currentSchema");
        }
        // Restrict unqualified DDL/history lookups to this schema, without identifier interpolation.
        // SET LOCAL rolls back to the host search path at commit/rollback.
        try (PreparedStatement statement = connection.prepareStatement("select set_config('search_path', quote_ident(?), true)")) {
            statement.setString(1, schema);
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(?, hashtext(?))")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setString(2, schema);
            statement.execute();
        }
    }

    private static void verifyOwnership(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                select c.relname from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname=current_schema() and c.relkind in ('r','p')
                """)) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        if (tables.contains("flyway_schema_history") || tables.contains("databasechangelog")
                || tables.contains("databasechangeloglock")
                || (!tables.contains("jworkflow_schema_history") && tables.stream()
                    .anyMatch(name -> name.startsWith("workflow_") || name.equals("event_status")))) {
            throw new SQLException("Schema has an external or unowned migration history; disable built-in initialization and retain its migration owner");
        }
    }

    private static void verifyKnownVersions(Connection connection, List<Migration> migrations) throws SQLException {
        Set<Integer> known = new HashSet<>();
        migrations.forEach(migration -> known.add(migration.version()));
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select version from jworkflow_schema_history")) {
            while (rows.next()) if (!known.contains(rows.getInt(1))) {
                throw new SQLException("Schema contains a migration version unsupported by this runtime");
            }
        }
    }

    private static void apply(Connection connection, Migration migration) throws SQLException, java.io.IOException, java.security.NoSuchAlgorithmException {
        String sql = JdbcSchemaInitializer.read(migration.resource());
        String checksum = JdbcSchemaInitializer.sha256(sql);
        try (PreparedStatement statement = connection.prepareStatement("select checksum from jworkflow_schema_history where version=?")) {
            statement.setInt(1, migration.version());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    if (!checksum.equals(rows.getString(1))) throw new SQLException("Applied PostgreSQL migration V" + migration.version() + " checksum differs");
                    return;
                }
            }
        }
        for (String command : JdbcSchemaInitializer.statements(sql)) execute(connection, command);
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into jworkflow_schema_history (version,description,checksum,installed_at) values (?,?,?,?)
                """)) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, checksum);
            statement.setBigDecimal(4, PostgresqlInstantCodec.encode(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void restore(Connection connection, boolean readOnly, int isolation, Throwable primary) throws SQLException {
        SQLException cleanup = null;
        try { connection.setAutoCommit(true); } catch (SQLException failure) { cleanup = failure; }
        try { connection.setReadOnly(readOnly); } catch (SQLException failure) {
            if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure);
        }
        try { connection.setTransactionIsolation(isolation); } catch (SQLException failure) {
            if (cleanup == null) cleanup = failure; else cleanup.addSuppressed(failure);
        }
        if (cleanup != null) {
            if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
        }
    }

    /**
     * PostgreSQL migration version, description and immutable classpath resource identity.
     * @param version declared workflow or format version
     * @param description the description
     * @param resource the resource
     */
    record Migration(int version, String description, String resource) { }
}
