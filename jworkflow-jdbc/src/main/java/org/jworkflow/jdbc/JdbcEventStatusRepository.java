package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC append-only event-status history participating in the bundle's transaction connection.
 */
final class JdbcEventStatusRepository implements EventStatusRepository {
    private final JdbcConnectionFactory connectionFactory;

    JdbcEventStatusRepository(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void append(EventStatusAttempt attempt) {
        String sql = """
                insert into event_status (
                    id,
                    event_id,
                    attempt_id,
                    attempt_number,
                    idempotency_key,
                    workflow_instance_id,
                    correlation_id,
                    status_scope,
                    handler_id,
                    destination,
                    status_value,
                    retry_count,
                    next_retry_at,
                    retry_eligible,
                    terminal,
                    last_error_code,
                    last_error_message,
                    created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attempt.statusId().toString());
            statement.setString(2, attempt.eventId().toString());
            statement.setString(3, attempt.attemptId().toString());
            statement.setInt(4, attempt.attemptNumber());
            statement.setString(5, connectionFactory.strategy().encodeIdempotencyKey(attempt.idempotencyKey()));
            statement.setString(6, attempt.workflowInstanceId() == null ? null : attempt.workflowInstanceId().toString());
            statement.setString(7, attempt.correlationId());
            statement.setString(8, attempt.scope().name());
            statement.setString(9, attempt.handlerId());
            statement.setString(10, attempt.destination());
            statement.setString(11, attempt.status().name());
            statement.setInt(12, attempt.retryCount());
            connectionFactory.strategy().bindInstant(statement, 13, attempt.nextRetryAt());
            statement.setInt(14, attempt.retryEligible() ? 1 : 0);
            statement.setInt(15, attempt.terminal() ? 1 : 0);
            statement.setString(16, attempt.lastErrorCode());
            statement.setString(17, attempt.lastErrorMessage());
            connectionFactory.strategy().bindInstant(statement, 18, attempt.createdAt());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Event status append affected an unexpected number of rows");
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to append event status attempt " + attempt.attemptId(), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<EventStatusAttempt> findAttempts(UUID eventId) {
        String sql = """
                select
                    id,
                    event_id,
                    attempt_id,
                    attempt_number,
                    idempotency_key,
                    workflow_instance_id,
                    correlation_id,
                    status_scope,
                    handler_id,
                    destination,
                    status_value,
                    retry_count,
                    next_retry_at,
                    retry_eligible,
                    terminal,
                    last_error_code,
                    last_error_message,
                    created_at
                from event_status
                where event_id = ?
                order by attempt_number,id
                """;
        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<EventStatusAttempt> attempts = new ArrayList<>();
                while (resultSet.next()) {
                    attempts.add(new EventStatusAttempt(
                            UUID.fromString(resultSet.getString("id")),
                            UUID.fromString(resultSet.getString("event_id")),
                            UUID.fromString(resultSet.getString("attempt_id")),
                            resultSet.getInt("attempt_number"),
                            connectionFactory.strategy().decodeIdempotencyKey(resultSet.getString("idempotency_key")),
                            workflowInstanceId(resultSet.getString("workflow_instance_id")),
                            resultSet.getString("correlation_id"),
                            EventStatusScope.valueOf(resultSet.getString("status_scope")),
                            resultSet.getString("handler_id"),
                            resultSet.getString("destination"),
                            EventStatusValue.valueOf(resultSet.getString("status_value")),
                            resultSet.getInt("retry_count"),
                            connectionFactory.strategy().readInstant(resultSet, "next_retry_at"),
                            resultSet.getInt("retry_eligible") == 1,
                            resultSet.getInt("terminal") == 1,
                            resultSet.getString("last_error_code"),
                            resultSet.getString("last_error_message"),
                            connectionFactory.strategy().readInstant(resultSet, "created_at")));
                }
                return attempts;
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to load event status attempts for event " + eventId, exception);
        }
    }

    private static WorkflowInstanceId workflowInstanceId(String value) {
        return value == null ? null : new WorkflowInstanceId(UUID.fromString(value));
    }

}
