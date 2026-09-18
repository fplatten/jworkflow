package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class JdbcEventStatusRepository implements EventStatusRepository {
    private final JdbcConnectionFactory connectionFactory;

    JdbcEventStatusRepository(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

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
            statement.setString(5, attempt.idempotencyKey());
            statement.setString(6, attempt.workflowInstanceId() == null ? null : attempt.workflowInstanceId().toString());
            statement.setString(7, attempt.correlationId());
            statement.setString(8, attempt.scope().name());
            statement.setString(9, attempt.handlerId());
            statement.setString(10, attempt.destination());
            statement.setString(11, attempt.status().name());
            statement.setInt(12, attempt.retryCount());
            statement.setString(13, attempt.nextRetryAt() == null ? null : attempt.nextRetryAt().toString());
            statement.setInt(14, attempt.retryEligible() ? 1 : 0);
            statement.setInt(15, attempt.terminal() ? 1 : 0);
            statement.setString(16, attempt.lastErrorCode());
            statement.setString(17, attempt.lastErrorMessage());
            statement.setString(18, attempt.createdAt().toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Event status append affected an unexpected number of rows");
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to append event status attempt " + attempt.attemptId(), exception);
        }
    }

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
                order by attempt_number
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
                            resultSet.getString("idempotency_key"),
                            workflowInstanceId(resultSet.getString("workflow_instance_id")),
                            resultSet.getString("correlation_id"),
                            EventStatusScope.valueOf(resultSet.getString("status_scope")),
                            resultSet.getString("handler_id"),
                            resultSet.getString("destination"),
                            EventStatusValue.valueOf(resultSet.getString("status_value")),
                            resultSet.getInt("retry_count"),
                            instant(resultSet.getString("next_retry_at")),
                            resultSet.getInt("retry_eligible") == 1,
                            resultSet.getInt("terminal") == 1,
                            resultSet.getString("last_error_code"),
                            resultSet.getString("last_error_message"),
                            Instant.parse(resultSet.getString("created_at"))));
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

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
