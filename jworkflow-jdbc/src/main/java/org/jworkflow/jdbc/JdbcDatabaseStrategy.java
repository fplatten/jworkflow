package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowEngine;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;

/** Database-specific connection and initialization policy shared by a persistence bundle. */
interface JdbcDatabaseStrategy {
    /**
     * Returns the durable backend implemented by this strategy.
     * @return the durable backend implemented by this strategy
     */
    WorkflowEngine.Type type();
    /**
     * Applies adapter connection settings before use; the factory closes the connection if setup fails.
     * @param connection transaction-bound JDBC connection; ownership remains with the enclosing adapter
     * @throws SQLException if the database operation fails
     */
    void configure(Connection connection) throws SQLException;
    /**
     * Initializes the selected database resource tree under the built-in migration owner.
     * @param factory shared connection factory and database strategy
     */
    void initializeSchema(JdbcConnectionFactory factory);

    /**
     * Returns the false.
     * @return the false
     */
    default boolean usesImmediateWriteTransaction() { return false; }
    /**
     * Returns the JDBC isolation level required for adapter transactions.
     * @return the JDBC isolation level required for adapter transactions
     */
    default int transactionIsolation() { return Connection.TRANSACTION_READ_COMMITTED; }
    /**
     * Checks database-specific transaction health before attempting commit.
     * @param connection transaction-bound JDBC connection; ownership remains with the enclosing adapter
     * @throws SQLException if the database operation fails
     */
    default void beforeCommit(Connection connection) throws SQLException { }
    /**
     * Protects the complete command key through the current transaction; SQLite relies on its write transaction.
     * @param connection transaction-bound JDBC connection; ownership remains with the enclosing adapter
     * @param key complete idempotency key
     * @throws SQLException if the database operation fails
     */
    default void lockCommand(Connection connection, String key) throws SQLException { }
    /**
     * Returns atomic claim SQL for this queue, or null to select the serialized SQLite claim path.
     * @param queue fixed durable queue whose lease metadata is used
     * @param columns fixed projection of database columns required by the mapper
     * @return atomic claim SQL for this queue, or null to select the serialized SQLite claim path
     */
    default String claimBatchSql(JdbcLeaseSupport.Queue queue,String columns){return null;}
    /**
     * Returns SQL invalidating expired queue leases and clearing their owners and acquisition tokens.
     * @param queue fixed durable queue whose lease metadata is used
     * @return SQL invalidating expired queue leases and clearing their owners and acquisition tokens
     */
    default String releaseClaimsSql(JdbcLeaseSupport.Queue queue){
        return "update "+queue.table+" set status_value='RETRY_SCHEDULED',claimed_by=null,claim_until=null,claim_token=null"
                +(queue.timer?",updated_at=?":"")+" where status_value='CLAIMED' and claim_until<=?";
    }
    /**
     * Rejects owner-only completion when this backend requires acquisition-token fencing.
     */
    default void requireLegacyClaimSupport(){ }

    /**
     * Allocates the next per-instance sequence in the current write transaction. PostgreSQL uses a counter row;
     * SQLite relies on serialized writers.
     * @param connection transaction-bound JDBC connection; ownership remains with the enclosing adapter
     * @param instanceId workflow instance identity
     * @return the next per-instance sequence number
     * @throws SQLException if the database operation fails
     */
    default long nextEventSequence(Connection connection, String instanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select coalesce(max(sequence_number),0)+1 from workflow_event where workflow_instance_id=?")) {
            statement.setString(1, instanceId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Event sequence allocation returned no row");
                return rows.getLong(1);
            }
        }
    }
    /**
     * Converts a complete logical key to a reversible relational representation for this database.
     * @param value complete logical idempotency key
     * @return the resulting text
     */
    default String encodeIdempotencyKey(String value) { return value; }
    /**
     * Restores the complete logical key from its relational representation.
     * @param value encoded relational idempotency key
     * @return the resulting text
     */
    default String decodeIdempotencyKey(String value) { return value; }

    /**
     * Binds a nullable relational timestamp using the database storage contract.
     * @param statement prepared JDBC statement to bind or execute
     * @param index one-based JDBC parameter or column position
     * @param value timestamp to bind, or null for SQL NULL
     * @throws SQLException if the database operation fails
     */
    default void bindInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setString(index, value == null ? null : value.toString());
    }

    /**
     * Reads a nullable relational timestamp using the database storage contract.
     * @param rows JDBC result positioned at the row to decode
     * @param column one-based start column
     * @return the timestamp, or null when the stored timestamp is absent
     * @throws SQLException if the database operation fails
     */
    default Instant readInstant(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    /**
     * Reads a nullable relational timestamp using the database storage contract.
     * @param rows JDBC result positioned at the row to decode
     * @param column one-based start column
     * @return the timestamp, or null when the stored timestamp is absent
     * @throws SQLException if the database operation fails
     */
    default Instant readInstant(ResultSet rows, int column) throws SQLException {
        String value = rows.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    /**
     * Returns the JDBC type used to bind a null binary payload.
     * @return the JDBC type used to bind a null binary payload
     */
    default int binaryNullType() { return Types.BLOB; }

    /**
     * Both supported dialects implement targeted UPSERT; never suppress all constraint failures.
     * @param insert prepared insert to execute once
     * @param identityColumns fixed SQL projection for the duplicate identity lookup
     * @return the resulting text
     */
    default String insertIgnoringDuplicate(String insert, String identityColumns) {
        return insert + " on conflict (" + identityColumns + ") do nothing";
    }

    /**
     * Executes an insert while handling only the targeted duplicate case; unexpected constraint failures must
     * escape.
     * @param factory shared connection factory and database strategy
     * @param connection transaction-bound JDBC connection; ownership remains with the enclosing adapter
     * @param statement prepared JDBC statement to bind or execute
     * @param primaryConstraint expected primary-key constraint name; unrelated conflicts must propagate
     * @param winner lookup validating the committed duplicate winner
     * @return the execute duplicate insert
     * @throws SQLException if the database operation fails
     */
    default int executeDuplicateInsert(JdbcConnectionFactory factory,Connection connection,PreparedStatement statement,
                                      String primaryConstraint,JdbcDuplicateInsert.Winner winner) throws SQLException {
        return statement.executeUpdate();
    }

    /**
     * Creates a focused strategy for the selected JDBC backend, rejecting IN_MEMORY.
     * @param type selected engine backend
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     * @return the resulting jdbc database strategy
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    static JdbcDatabaseStrategy forType(WorkflowEngine.Type type, Map<String, String> settings) {
        return switch (type) {
            case SQLITE -> new SqliteDatabaseStrategy(settings);
            case POSTGRESQL -> new PostgresqlDatabaseStrategy(settings);
            case IN_MEMORY -> throw new IllegalArgumentException("In-memory workflows do not use JDBC");
        };
    }

    /**
     * Resolves supported database identity from JDBC metadata without exposing connection details in errors.
     * @param connection transaction-bound JDBC connection; ownership remains with the enclosing adapter
     * @return the resulting workflow engine.type
     * @throws SQLException if the database operation fails
     */
    static WorkflowEngine.Type productType(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        String product = metadata == null ? null : metadata.getDatabaseProductName();
        if ("SQLite".equalsIgnoreCase(product)) return WorkflowEngine.Type.SQLITE;
        if ("PostgreSQL".equalsIgnoreCase(product)) return WorkflowEngine.Type.POSTGRESQL;
        // Do not reflect arbitrary metadata or connection strings into diagnostics.
        throw new SQLException("Unsupported JDBC database product; expected SQLite or PostgreSQL");
    }

    /**
     * Loads only the selected backend driver, keeping the other driver optional.
     * @param type selected engine backend
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    static void loadDriver(WorkflowEngine.Type type) throws ClassNotFoundException {
        switch (type) {
            case SQLITE -> SqliteDatabaseStrategy.loadDriver();
            case POSTGRESQL -> PostgresqlDatabaseStrategy.loadDriver();
            case IN_MEMORY -> throw new IllegalArgumentException("In-memory workflows do not use JDBC");
        }
    }
}
