package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.persistence.*;

import java.sql.*;
import java.util.Optional;

/**
 * JDBC replay-result repository using the bundle's connection and database strategy. Expected duplicate inserts
 * return the validated stored winner; unrelated SQL failures are not swallowed.
 */
final class JdbcCommandResultRepository implements CommandResultRepository {
    private final JdbcConnectionFactory connections;
        private final JdbcJsonCodec json=new JdbcJsonCodec();
    JdbcCommandResultRepository(JdbcConnectionFactory connections){this.connections=connections;
    }
    /**
     * {@inheritDoc}
     */
    @Override public CommandResultRecord save(CommandResultRecord value){
        String sql=connections.strategy().insertIgnoringDuplicate("insert into workflow_command_result (idempotency_key,command_type,request_hash,workflow_instance_id,result_json,created_at) values (?,?,?,?,?,?)","idempotency_key");
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,connections.strategy().encodeIdempotencyKey(value.idempotencyKey()));
        s.setString(2,value.commandType());
        s.setString(3,value.requestHash());
        s.setString(4,value.workflowInstanceId()==null?null:value.workflowInstanceId().toString());
        s.setString(5,json.write(value.result()));
        connections.strategy().bindInstant(s, 6, value.createdAt());
        int affected=s.executeUpdate();
        if(affected==1)return value;
        if(affected!=0)throw new SQLException("Unexpected command result insert count");
        CommandResultRecord winner=find(c,value.idempotencyKey()).orElseThrow(()->new PersistenceConstraintException("Command result duplicate winner is no longer available"));
        if(!winner.commandType().equals(value.commandType())||!winner.requestHash().equals(value.requestHash()))
            throw new PersistenceConstraintException("Idempotency key was reused with different command content");
        return winner;
    }catch(SQLException x){
        throw new WorkflowInfrastructureException("Failed to save command result",x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public Optional<CommandResultRecord> find(String key){
        try(Connection c=connections.open()){return find(c,key);}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load command result",x);}
    }
    private Optional<CommandResultRecord> find(Connection c,String key)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("select idempotency_key,command_type,request_hash,workflow_instance_id,result_json,created_at from workflow_command_result where idempotency_key=?")){s.setString(1,connections.strategy().encodeIdempotencyKey(key));
        try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();
        String instance=r.getString(4);
        return Optional.of(new CommandResultRecord(connections.strategy().decodeIdempotencyKey(r.getString(1)),r.getString(2),r.getString(3),instance==null?null:WorkflowInstanceId.fromString(instance),json.readMap(r.getString(5)),connections.strategy().readInstant(r, 6)));
    }}}
}
