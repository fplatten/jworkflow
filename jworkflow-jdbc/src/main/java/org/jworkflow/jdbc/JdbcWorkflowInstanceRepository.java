package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.persistence.PersistenceSerializationException;
import org.jworkflow.persistence.WorkflowInstanceRepository;
import org.jworkflow.persistence.WorkflowOptimisticLockException;
import org.jworkflow.persistence.ActiveWorkflowCursor;
import org.jworkflow.persistence.PersistenceConstraintException;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class JdbcWorkflowInstanceRepository implements WorkflowInstanceRepository {
    private static final String COLUMNS = "id, workflow_key, workflow_version, workflow_revision, business_key, correlation_id, current_state, status, variables, lock_version, created_at, updated_at";
    private static final String TEXT_SELECT_PREFIX = "select ";
    private static final String TEXT_LIMIT_MUST_BE_POSITIVE = "limit must be positive";
    private final JdbcConnectionFactory connectionFactory;
    private final JdbcJsonCodec jsonCodec;

    JdbcWorkflowInstanceRepository(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, new JdbcJsonCodec());
    }

    JdbcWorkflowInstanceRepository(JdbcConnectionFactory connectionFactory, JdbcJsonCodec jsonCodec) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
    }

    @Override public void insert(WorkflowSnapshot snapshot) {
        String sql = """
                insert into workflow_instance (
                    id, workflow_key, workflow_version, workflow_revision, business_key, correlation_id,
                    current_state, status, variables, lock_version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshot.instanceId().toString());
            statement.setString(2, snapshot.workflowKey());
            statement.setString(3, snapshot.workflowVersion());
            statement.setString(4, snapshot.workflowRevision());
            statement.setString(5, snapshot.businessKey());
            statement.setString(6, snapshot.correlationId());
            statement.setString(7, snapshot.state());
            statement.setString(8, snapshot.status().name());
            statement.setString(9, jsonCodec.write(snapshot.variables()));
            statement.setLong(10, snapshot.lockVersion());
            statement.setString(11, snapshot.createdAt().toString());
            statement.setString(12, snapshot.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to insert workflow instance " + snapshot.instanceId(), exception);
        }
    }

    @Override public WorkflowSnapshot update(WorkflowSnapshot snapshot, long expectedLockVersion) {
        if (snapshot.lockVersion() != expectedLockVersion + 1) {
            throw new IllegalArgumentException("Updated snapshot lockVersion must equal expectedLockVersion + 1");
        }
        String sql = """
                update workflow_instance set current_state = ?, status = ?, variables = ?, correlation_id = ?,
                    updated_at = ?, lock_version = ? where id = ? and lock_version = ?
                """;
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshot.state());
            statement.setString(2, snapshot.status().name());
            statement.setString(3, jsonCodec.write(snapshot.variables()));
            statement.setString(4, snapshot.correlationId());
            statement.setString(5, snapshot.updatedAt().toString());
            statement.setLong(6, snapshot.lockVersion());
            statement.setString(7, snapshot.instanceId().toString());
            statement.setLong(8, expectedLockVersion);
            if (statement.executeUpdate() != 1) {
                throw new WorkflowOptimisticLockException(snapshot.instanceId(), expectedLockVersion,
                        observedVersion(connection, snapshot.instanceId()));
            }
            return snapshot;
        } catch (WorkflowOptimisticLockException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to update workflow instance " + snapshot.instanceId(), exception);
        }
    }

    @Override public Optional<WorkflowSnapshot> findById(WorkflowInstanceId id) {
        return findOne(TEXT_SELECT_PREFIX + COLUMNS + " from workflow_instance where id = ?", id.toString());
    }

    @Override public Optional<WorkflowSnapshot> findByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        List<WorkflowSnapshot> matches=queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where correlation_id=? order by updated_at,id limit 2",correlationId);
        if(matches.size()>1)throw new PersistenceConstraintException("Correlation ID is ambiguous without a workflow key: "+correlationId);
        return matches.stream().findFirst();
    }

    @Override public Optional<WorkflowSnapshot> findActiveById(WorkflowInstanceId id) {
        return findOne(TEXT_SELECT_PREFIX + COLUMNS + " from workflow_instance where id = ? and status in ('RUNNING','WAITING','FAILED')", id.toString());
    }

    @Override public List<WorkflowSnapshot> findActiveByCorrelation(String workflowKey,String correlationId,int limit) {
        requireRoute(workflowKey,correlationId,limit);
        return queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where workflow_key=? and correlation_id=? and status in ('RUNNING','WAITING','FAILED') order by updated_at,id limit ?",workflowKey,correlationId,limit);
    }

    @Override public List<WorkflowSnapshot> findActiveByBusinessKey(String workflowKey,String businessKey,int limit) {
        requireRoute(workflowKey,businessKey,limit);
        return queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where workflow_key=? and business_key=? and status in ('RUNNING','WAITING','FAILED') order by updated_at,id limit ?",workflowKey,businessKey,limit);
    }

    @Override public List<WorkflowSnapshot> findActiveAfter(ActiveWorkflowCursor cursor,int limit) {
        if(limit<1)throw new IllegalArgumentException(TEXT_LIMIT_MUST_BE_POSITIVE);
        if(cursor==null)return queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where status in ('RUNNING','WAITING','FAILED') order by updated_at,id limit ?",limit);
        return queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where status in ('RUNNING','WAITING','FAILED') and (updated_at>? or (updated_at=? and id>?)) order by updated_at,id limit ?",cursor.updatedAt().toString(),cursor.updatedAt().toString(),cursor.instanceId().toString(),limit);
    }

    @Override public Optional<WorkflowSnapshot> findByBusinessKey(String workflowKey, String businessKey) {
        if (workflowKey == null || workflowKey.isBlank() || businessKey == null || businessKey.isBlank()) return Optional.empty();
        String sql = TEXT_SELECT_PREFIX + COLUMNS + " from workflow_instance where workflow_key = ? and business_key = ? order by created_at desc limit 1";
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workflowKey);
            statement.setString(2, businessKey);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to load workflow instance by business key", exception);
        }
    }

    @Override public List<WorkflowSnapshot> findActive(int limit) {
        if (limit < 1) throw new IllegalArgumentException(TEXT_LIMIT_MUST_BE_POSITIVE);
        String sql = TEXT_SELECT_PREFIX + COLUMNS + " from workflow_instance where status in ('RUNNING','WAITING','FAILED') order by updated_at, id limit ?";
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<WorkflowSnapshot> result = new ArrayList<>();
                while (rows.next()) result.add(map(rows));
                return List.copyOf(result);
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to load active workflow instances", exception);
        }
    }

    @Override public List<WorkflowSnapshot> findAll(int limit, int offset) {
        requirePage(limit,offset);
            return queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance order by created_at,id limit ? offset ?",limit,offset);
    }

    @Override public List<WorkflowSnapshot> findByStatus(WorkflowStatus status,int limit) {
        Objects.requireNonNull(status,"status");
            if(limit<1)throw new IllegalArgumentException(TEXT_LIMIT_MUST_BE_POSITIVE);
        return queryMany(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where status=? order by updated_at,id limit ?",status.name(),limit);
    }

    @Override public List<WorkflowSnapshot> findStuck(Instant updatedBefore,int limit) {
        Objects.requireNonNull(updatedBefore,"updatedBefore");
            if(limit<1)throw new IllegalArgumentException(TEXT_LIMIT_MUST_BE_POSITIVE);
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_instance where status in ('RUNNING','WAITING') and updated_at<=? order by updated_at,id limit ?";
        return queryMany(sql,updatedBefore.toString(),limit);
    }

    private Optional<WorkflowSnapshot> findOne(String sql, String parameter) {
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to load workflow instance", exception);
        }
    }

    private List<WorkflowSnapshot> queryMany(String sql,Object... parameters){try(Connection c=connectionFactory.open();
        PreparedStatement s=c.prepareStatement(sql)){for(int i=0;
        i<parameters.length;
        i++){Object value=parameters[i];
        if(value instanceof Integer n)s.setInt(i+1,n);
        else s.setString(i+1,String.valueOf(value));
    }
    try(ResultSet rows=s.executeQuery()){ArrayList<WorkflowSnapshot> result=new ArrayList<>();
        while(rows.next())result.add(map(rows));
        return List.copyOf(result);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed workflow instance query",x);
    }}
    private static void requirePage(int limit,int offset){if(limit<1)throw new IllegalArgumentException(TEXT_LIMIT_MUST_BE_POSITIVE);
        if(offset<0)throw new IllegalArgumentException("offset must not be negative");
    }
    private static void requireRoute(String workflowKey,String value,int limit){if(workflowKey==null||workflowKey.isBlank())throw new IllegalArgumentException("workflowKey is required");
        if(value==null||value.isBlank())throw new IllegalArgumentException("routing value is required");
        if(limit<1)throw new IllegalArgumentException(TEXT_LIMIT_MUST_BE_POSITIVE);
    }

    private WorkflowSnapshot map(ResultSet row) throws SQLException {
        return new WorkflowSnapshot(
                new WorkflowInstanceId(UUID.fromString(row.getString("id"))), row.getString("workflow_key"),
                row.getString("workflow_version"), required(row.getString("workflow_revision"), "workflow_revision"),
                row.getString("business_key"), row.getString("correlation_id"), row.getString("current_state"),
                WorkflowStatus.valueOf(row.getString("status")), jsonCodec.readPersistedMap(row.getString("variables")),
                row.getLong("lock_version"), Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")));
    }

    private Long observedVersion(Connection connection, WorkflowInstanceId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select lock_version from workflow_instance where id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static String required(String value, String column) {
        if (value == null || value.isBlank()) throw new PersistenceSerializationException("Required persisted column is missing: " + column);
        return value;
    }
}
