package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.persistence.*;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;

final class JdbcCommandResultRepository implements CommandResultRepository {
    private final JdbcConnectionFactory connections;private final JdbcJsonCodec json=new JdbcJsonCodec();
    JdbcCommandResultRepository(JdbcConnectionFactory connections){this.connections=connections;}
    @Override public CommandResultRecord save(CommandResultRecord value){Optional<CommandResultRecord> existing=find(value.idempotencyKey());if(existing.isPresent()){CommandResultRecord found=existing.get();if(!found.commandType().equals(value.commandType())||!found.requestHash().equals(value.requestHash()))throw new PersistenceConstraintException("Idempotency key was reused with different command content");return found;}String sql="insert into workflow_command_result (idempotency_key,command_type,request_hash,workflow_instance_id,result_json,created_at) values (?,?,?,?,?,?)";try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,value.idempotencyKey());s.setString(2,value.commandType());s.setString(3,value.requestHash());s.setString(4,value.workflowInstanceId()==null?null:value.workflowInstanceId().toString());s.setString(5,json.write(value.result()));s.setString(6,value.createdAt().toString());if(s.executeUpdate()!=1)throw new SQLException("Unexpected command result insert count");return value;}catch(SQLException x){Optional<CommandResultRecord> raced=find(value.idempotencyKey());if(raced.isPresent()&&raced.get().requestHash().equals(value.requestHash())&&raced.get().commandType().equals(value.commandType()))return raced.get();throw new WorkflowInfrastructureException("Failed to save command result",x);}}
    @Override public Optional<CommandResultRecord> find(String key){try(Connection c=connections.open();PreparedStatement s=c.prepareStatement("select idempotency_key,command_type,request_hash,workflow_instance_id,result_json,created_at from workflow_command_result where idempotency_key=?")){s.setString(1,key);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();String instance=r.getString(4);return Optional.of(new CommandResultRecord(r.getString(1),r.getString(2),r.getString(3),instance==null?null:WorkflowInstanceId.fromString(instance),json.readMap(r.getString(5)),Instant.parse(r.getString(6))));}}catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load command result",x);}}
}
