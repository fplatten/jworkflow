package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;

/** PostgreSQL connection, schema and relational-value policy. */
final class PostgresqlDatabaseStrategy implements JdbcDatabaseStrategy {
    private final int commandLockTimeoutMs;
    /**
     * {@inheritDoc}
     */
    @Override public int executeDuplicateInsert(JdbcConnectionFactory factory,Connection connection,PreparedStatement statement,
                                               String primaryConstraint,JdbcDuplicateInsert.Winner winner) throws SQLException {
        return JdbcDuplicateInsert.execute(factory,connection,statement,primaryConstraint,winner);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void requireLegacyClaimSupport(){
        throw new UnsupportedOperationException("PostgreSQL completion requires the token-aware lease API");
    }
    /**
     * {@inheritDoc}
     */
    @Override public String claimBatchSql(JdbcLeaseSupport.Queue queue,String columns){
        return "with candidates as (select id from "+queue.table+" where "+queue.eligible()
                +" order by "+queue.order+" limit ? for update skip locked), acquired as (update "+queue.table
                +" q set status_value='CLAIMED',claimed_by=?,claim_until=?,claim_token=gen_random_uuid()::text"
                +(queue.timer?",updated_at=?":"")+" from candidates c where q.id=c.id returning q.*) select "+columns
                +" from acquired order by "+queue.order;
    }
    /**
     * {@inheritDoc}
     */
    @Override public String releaseClaimsSql(JdbcLeaseSupport.Queue queue){
        return "with expired as (select id from "+queue.table+" where status_value='CLAIMED' and claim_until<=?"
                +" order by claim_until,id limit 1000 for update skip locked) update "+queue.table
                +" q set status_value='RETRY_SCHEDULED',claimed_by=null,claim_until=null,claim_token=null"
                +(queue.timer?",updated_at=?":"")+" from expired e where q.id=e.id";
    }

    /**
     * {@inheritDoc}
     */
    @Override public void lockCommand(Connection connection, String key) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Command lock requires a transaction");
        String schema;
        long previous;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "select current_schema(), setting::bigint from pg_settings where name='lock_timeout'")) {
            if (!rows.next() || (schema = rows.getString(1)) == null)
                throw new SQLException("Command lock requires a current schema");
            previous = rows.getLong(2);
        }
        long timeout = previous == 0 ? commandLockTimeoutMs : Math.min(previous, commandLockTimeoutMs);
        setLockTimeout(connection, timeout);
        try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
            statement.setLong(1, commandLockIdentity(schema, key));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Command lock returned no row");
            }
        }
        // On failure PostgreSQL has aborted the transaction: rollback restores the local setting.
        setLockTimeout(connection, previous);
    }

    private static void setLockTimeout(Connection connection, long milliseconds) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('lock_timeout', ?, true)")) {
            statement.setString(1, milliseconds + "ms");
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Command lock timeout configuration returned no row");
            }
        }
    }

    static long commandLockIdentity(String schema, String key) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0); // A PostgreSQL schema name cannot contain NUL.
            digest.update(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest.digest()).getLong();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public long nextEventSequence(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into workflow_event_sequence(workflow_instance_id,last_sequence) values (?,1)
                on conflict (workflow_instance_id) do update
                set last_sequence=workflow_event_sequence.last_sequence+1 returning last_sequence
                """)) {
            statement.setString(1, instanceId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Event sequence allocation returned no row");
                return rows.getLong(1);
            }
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public String encodeIdempotencyKey(String value) {
        return value == null ? null : value.replace("%", "%25").replace("\u0000", "%00");
    }
    /**
     * {@inheritDoc}
     */
    @Override public String decodeIdempotencyKey(String value) {
        return value == null ? null : value.replace("%00", "\u0000").replace("%25", "%");
    }
    /**
     * {@inheritDoc}
     */
    @Override public void beforeCommit(Connection connection) throws SQLException {
        // PostgreSQL COMMIT on an aborted transaction can return ROLLBACK without an error.
        // Detect an error swallowed by caller code before declaring success or delivering observers.
        try (var statement = connection.createStatement(); var result = statement.executeQuery("select 1")) {
            if (!result.next()) throw new SQLException("PostgreSQL transaction validation failed");
        }
    }
    PostgresqlDatabaseStrategy(Map<String, String> settings) {
        commandLockTimeoutMs = Integer.parseInt(settings.getOrDefault("postgres.command-lock-timeout-ms", "5000"));
        if (commandLockTimeoutMs < 1) throw new IllegalArgumentException("postgres.command-lock-timeout-ms must be positive");
        if (settings.containsKey("sqlite.busy-timeout-ms") || settings.containsKey("sqlite.wal-enabled")) {
            throw new IllegalArgumentException("POSTGRESQL does not accept explicit SQLite settings");
        }
    }

    static void loadDriver() throws ClassNotFoundException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException failure) {
            throw new ClassNotFoundException("POSTGRESQL requires org.postgresql:postgresql or a supplied Driver/DataSource", failure);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public WorkflowEngine.Type type() { return WorkflowEngine.Type.POSTGRESQL; }

    /**
     * {@inheritDoc}
     */
    @Override public void configure(Connection connection) {
        // Credentials, schema, TLS and pooling belong to the host; no SQLite operations.
    }

    /**
     * {@inheritDoc}
     */
    @Override public void initializeSchema(JdbcConnectionFactory factory) {
        PostgresqlSchemaInitializer.initialize(factory);
    }

    /**
     * {@inheritDoc}
     */
    @Override public void bindInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, Types.NUMERIC);
        else statement.setBigDecimal(index, PostgresqlInstantCodec.encode(value));
    }

    /**
     * {@inheritDoc}
     */
    @Override public Instant readInstant(ResultSet rows, String column) throws SQLException {
        return PostgresqlInstantCodec.decode(rows.getBigDecimal(column));
    }

    /**
     * {@inheritDoc}
     */
    @Override public Instant readInstant(ResultSet rows, int column) throws SQLException {
        return PostgresqlInstantCodec.decode(rows.getBigDecimal(column));
    }

    /**
     * {@inheritDoc}
     */
    @Override public int binaryNullType() { return Types.BINARY; }

}
