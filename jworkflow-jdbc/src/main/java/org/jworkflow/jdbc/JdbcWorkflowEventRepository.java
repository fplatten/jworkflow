package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.events.*;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.persistence.WorkflowEventRepository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class JdbcWorkflowEventRepository implements WorkflowEventRepository {
    private static final String COLUMNS = "id,event_type,source_system,correlation_id,causation_id,trace_id,workflow_instance_id,business_key,tenant_id,taxonomy_version,occurred_at,received_at,headers,message_payload,message_content_type,message_schema_name,message_schema_version,message_redaction_status,message_payload_blob";
    private static final String TEXT_SELECT_PREFIX = "select ";
    private final JdbcConnectionFactory connections;
    private final JdbcJsonCodec json;
    JdbcWorkflowEventRepository(JdbcConnectionFactory connections) { this(connections,new JdbcJsonCodec());
    }
    JdbcWorkflowEventRepository(JdbcConnectionFactory connections, JdbcJsonCodec json) { this.connections=connections;
        this.json=json;
    }

    @Override public void append(WorkflowEvent event) {
        if (connections.currentTransactionConnection() == null) {
            new JdbcTransactionManager(connections).inWriteTransaction(() -> { appendWithinTransaction(event); return null; });
        } else appendWithinTransaction(event);
    }

    private void appendWithinTransaction(WorkflowEvent event) {
        String sql="""
                insert into workflow_event (id,event_type,subject,action,source_system,correlation_id,causation_id,trace_id,
                workflow_instance_id,business_key,tenant_id,taxonomy_version,occurred_at,received_at,headers,message_payload,
                message_content_type,message_schema_name,message_schema_version,message_redaction_status,sequence_number,metadata_json,message_payload_blob)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try(Connection connection=connections.open();
            PreparedStatement statement=connection.prepareStatement(sql)) {
            EventMetadata m=event.metadata();
                EventMessage message=event.message();
            Long sequence=m.workflowInstanceId()==null?null:connections.strategy().nextEventSequence(connection,m.workflowInstanceId().toString());
            statement.setString(1,m.eventId().toString());
                statement.setString(2,m.eventName().value());
                statement.setString(3,m.eventName().subject());
            statement.setString(4,m.eventName().action());
                statement.setString(5,m.sourceSystem());
                statement.setString(6,m.correlationId());
            statement.setString(7,m.causationId());
                statement.setString(8,m.traceId());
            statement.setString(9,m.workflowInstanceId()==null?null:m.workflowInstanceId().toString());
                statement.setString(10,m.businessKey());
            statement.setString(11,m.tenantId());
                statement.setString(12,m.taxonomyVersion());
                connections.strategy().bindInstant(statement, 13, m.occurredAt());
            connections.strategy().bindInstant(statement, 14, m.receivedAt());
                String metadata=json.write(m.headers());
                statement.setString(15,metadata);
            Object payload=message.payload();
                boolean binary=payload instanceof byte[];
            LinkedHashMap<String,Object> body=new LinkedHashMap<>();
                body.put("attributes",message.attributes());
                body.put("payload",binary?null:payload);
            statement.setString(16,json.write(body));
                statement.setString(17,message.contentType());
                statement.setString(18,message.schemaName());
            statement.setString(19,message.schemaVersion());
                statement.setString(20,message.redacted()?"REDACTED":"VISIBLE");
            if(sequence==null) statement.setNull(21,Types.BIGINT);
                else statement.setLong(21,sequence);
            statement.setString(22,metadata);
                if(binary) statement.setBytes(23,json.copyBinary((byte[])payload));
                else statement.setNull(23,connections.strategy().binaryNullType());
            if(statement.executeUpdate()!=1) throw new SQLException("Event insert affected an unexpected number of rows");
        } catch(SQLException failure){ throw new WorkflowInfrastructureException("Failed to append workflow event "+event.metadata().eventId(),failure);
        }
    }

    @Override public Optional<WorkflowEvent> find(UUID eventId) {
        try(Connection connection=connections.open();
            PreparedStatement statement=connection.prepareStatement(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_event where id=?")) {
            statement.setString(1,eventId.toString());
                try(ResultSet rows=statement.executeQuery()){ return rows.next()?Optional.of(map(rows)):Optional.empty();
            }
        } catch(SQLException failure){ throw new WorkflowInfrastructureException("Failed to read workflow event "+eventId,failure);
        }
    }

    @Override public List<WorkflowEvent> findByWorkflowInstance(WorkflowInstanceId instanceId) {
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_event where workflow_instance_id=? order by sequence_number nulls first,id";
        try(Connection connection=connections.open();
            PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,instanceId.toString());
                try(ResultSet rows=statement.executeQuery()){
                ArrayList<WorkflowEvent> result=new ArrayList<>();
                    while(rows.next()) result.add(map(rows));
                    return List.copyOf(result);
            }
        } catch(SQLException failure){ throw new WorkflowInfrastructureException("Failed to load workflow event history for "+instanceId,failure);
        }
    }

    @Override public List<WorkflowEvent> findAllAfter(Instant after,int limit){if(limit<1)throw new IllegalArgumentException("limit must be positive");
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_event where occurred_at>? order by occurred_at,id limit ?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s,1,after==null?Instant.EPOCH:after);
        s.setInt(2,limit);
        try(ResultSet rows=s.executeQuery()){ArrayList<WorkflowEvent> result=new ArrayList<>();
        while(rows.next())result.add(map(rows));
        return List.copyOf(result);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed workflow event stream query",x);
    }}

    private WorkflowEvent map(ResultSet row)throws SQLException{
        EventMetadata metadata=new EventMetadata(UUID.fromString(row.getString("id")),new EventName(row.getString("event_type")),row.getString("source_system"),
                row.getString("correlation_id"),row.getString("causation_id"),row.getString("trace_id"),instance(row.getString("workflow_instance_id")),
                row.getString("business_key"),row.getString("tenant_id"),row.getString("taxonomy_version"),connections.strategy().readInstant(row, "occurred_at"),
                connections.strategy().readInstant(row, "received_at"),strings(json.readPersistedMap(row.getString("headers"))));
        Map<String,Object> stored=json.readMap(row.getString("message_payload"));
            byte[] binary=row.getBytes("message_payload_blob");
        Object payload=binary==null?stored.get("payload"):json.copyBinary(binary);
        EventMessage message=new EventMessage(payload,row.getString("message_content_type"),row.getString("message_schema_name"),row.getString("message_schema_version"),
                "REDACTED".equals(row.getString("message_redaction_status")),strings(stored.get("attributes")));
        return new WorkflowEvent(metadata,message);
    }

    private static WorkflowInstanceId instance(String value){return value==null?null:WorkflowInstanceId.fromString(value);
    }
    private static Map<String,String> strings(Object value){if(!(value instanceof Map<?,?> map))return Map.of();
        LinkedHashMap<String,String> result=new LinkedHashMap<>();
        map.forEach((k,v)->result.put(String.valueOf(k),v==null?null:String.valueOf(v)));
        return Collections.unmodifiableMap(result);
    }
}
