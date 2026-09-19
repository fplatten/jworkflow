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
    WorkflowEngine.Type type();
    void configure(Connection connection) throws SQLException;
    void initializeSchema(JdbcConnectionFactory factory);

    default boolean usesImmediateWriteTransaction() { return false; }
    default int transactionIsolation() { return Connection.TRANSACTION_READ_COMMITTED; }
    default void beforeCommit(Connection connection) throws SQLException { }
    default void lockCommand(Connection connection, String key) throws SQLException { }
    default String claimBatchSql(JdbcLeaseSupport.Queue queue,String columns){return null;}
    default String releaseClaimsSql(JdbcLeaseSupport.Queue queue){
        return "update "+queue.table+" set status_value='RETRY_SCHEDULED',claimed_by=null,claim_until=null,claim_token=null"
                +(queue.timer?",updated_at=?":"")+" where status_value='CLAIMED' and claim_until<=?";
    }
    default void requireLegacyClaimSupport(){ }

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
    default String encodeIdempotencyKey(String value) { return value; }
    default String decodeIdempotencyKey(String value) { return value; }

    default void bindInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setString(index, value == null ? null : value.toString());
    }

    default Instant readInstant(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    default Instant readInstant(ResultSet rows, int column) throws SQLException {
        String value = rows.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    default int binaryNullType() { return Types.BLOB; }

    /** Both supported dialects implement targeted UPSERT; never suppress all constraint failures. */
    default String insertIgnoringDuplicate(String insert, String identityColumns) {
        return insert + " on conflict (" + identityColumns + ") do nothing";
    }

    default int executeDuplicateInsert(JdbcConnectionFactory factory,Connection connection,PreparedStatement statement,
                                      String primaryConstraint,JdbcDuplicateInsert.Winner winner) throws SQLException {
        return statement.executeUpdate();
    }

    static JdbcDatabaseStrategy forType(WorkflowEngine.Type type, Map<String, String> settings) {
        return switch (type) {
            case SQLITE -> new SqliteDatabaseStrategy(settings);
            case POSTGRESQL -> new PostgresqlDatabaseStrategy(settings);
            case IN_MEMORY -> throw new IllegalArgumentException("In-memory workflows do not use JDBC");
        };
    }

    static WorkflowEngine.Type productType(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        String product = metadata == null ? null : metadata.getDatabaseProductName();
        if ("SQLite".equalsIgnoreCase(product)) return WorkflowEngine.Type.SQLITE;
        if ("PostgreSQL".equalsIgnoreCase(product)) return WorkflowEngine.Type.POSTGRESQL;
        // Do not reflect arbitrary metadata or connection strings into diagnostics.
        throw new SQLException("Unsupported JDBC database product; expected SQLite or PostgreSQL");
    }

    static void loadDriver(WorkflowEngine.Type type) throws ClassNotFoundException {
        switch (type) {
            case SQLITE -> SqliteDatabaseStrategy.loadDriver();
            case POSTGRESQL -> PostgresqlDatabaseStrategy.loadDriver();
            case IN_MEMORY -> throw new IllegalArgumentException("In-memory workflows do not use JDBC");
        }
    }
}
