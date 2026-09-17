package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.events.EventName;
import org.jworkflow.model.*;
import org.jworkflow.persistence.PersistenceConstraintException;
import org.jworkflow.persistence.WorkflowTimerRepository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class JdbcWorkflowTimerRepository implements WorkflowTimerRepository {
    private final JdbcConnectionFactory connections;
    JdbcWorkflowTimerRepository(JdbcConnectionFactory connections){this.connections=connections;}

    @Override public void save(WorkflowTimer timer){
        String sql="""
                insert into workflow_timer (id,workflow_instance_id,timer_type,due_at,status_value,created_at,step_name,target_node,emitted_event,
                attempt_count,next_attempt_at,claimed_by,claim_until,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set due_at=excluded.due_at,status_value=excluded.status_value,step_name=excluded.step_name,
                target_node=excluded.target_node,emitted_event=excluded.emitted_event,attempt_count=excluded.attempt_count,
                next_attempt_at=excluded.next_attempt_at,claimed_by=excluded.claimed_by,claim_until=excluded.claim_until,updated_at=excluded.updated_at
                """;
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){bind(s,timer);if(s.executeUpdate()!=1)throw new SQLException("Unexpected timer upsert count");}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to save timer "+timer.timerId(),x);}
    }

    @Override public List<WorkflowTimer> dueTimers(Instant now){return queryEligible(now,Integer.MAX_VALUE);}

    @Override public List<WorkflowTimer> findByWorkflowInstance(WorkflowInstanceId instanceId){
        String sql="select "+columns()+" from workflow_timer where workflow_instance_id=? order by created_at,id";
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,instanceId.toString());try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimer> out=new ArrayList<>();while(r.next())out.add(map(r));return List.copyOf(out);}}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load timers for "+instanceId,x);}
    }
    @Override public List<WorkflowTimer> findPending(int limit){if(limit<1)throw new IllegalArgumentException("limit must be positive");String sql="select "+columns()+" from workflow_timer where status_value in ('PENDING','CLAIMED','RETRY_SCHEDULED') order by coalesce(next_attempt_at,due_at),created_at,id limit ?";try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,limit);try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimer> out=new ArrayList<>();while(r.next())out.add(map(r));return List.copyOf(out);}}catch(SQLException x){throw new WorkflowInfrastructureException("Failed pending timer query",x);}}

    @Override public List<WorkflowTimer> claimDue(Instant now,String owner,Instant until,int limit){
        connections.requireImmediateTransaction("Timer claiming"); requireOwner(owner,until,now,limit); ArrayList<WorkflowTimer> claimed=new ArrayList<>();
        try(Connection c=connections.open()){
            for(WorkflowTimer timer:queryEligible(c,now,limit)){
                String sql="update workflow_timer set status_value='CLAIMED',claimed_by=?,claim_until=?,updated_at=? where id=? and status_value in ('PENDING','RETRY_SCHEDULED') and (claim_until is null or claim_until<=?)";
                try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,owner);s.setString(2,until.toString());s.setString(3,now.toString());s.setString(4,timer.timerId().toString());s.setString(5,now.toString());
                    if(s.executeUpdate()==1)claimed.add(new WorkflowTimer(timer.timerId(),timer.workflowInstanceId(),timer.stepName(),timer.dueAt(),timer.targetNode(),timer.emittedEvent(),WorkflowTimerStatus.CLAIMED,timer.attemptCount(),timer.nextAttemptAt(),owner,until,timer.createdAt(),now));}
            }
            return List.copyOf(claimed);
        }catch(SQLException x){throw new WorkflowInfrastructureException("Failed to claim due timers",x);}
    }

    @Override public void markFired(UUID id,String owner,Instant at){guarded(id,owner,"update workflow_timer set status_value='FIRED',claimed_by=null,claim_until=null,updated_at=? where id=? and status_value='CLAIMED' and claimed_by=?",at,null);}
    @Override public void markFailed(UUID id,String owner,String error,Instant next){
        String sql="update workflow_timer set status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,updated_at=? where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,next.toString());s.setString(2,error);s.setString(3,Instant.now().toString());s.setString(4,id.toString());s.setString(5,owner);one(s,id,"timer failure");}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to schedule timer retry "+id,x);}
    }
    @Override public void cancel(UUID id,Instant at){
        String sql="update workflow_timer set status_value='CANCELED',claimed_by=null,claim_until=null,updated_at=? where id=? and status_value not in ('FIRED','CANCELED','DEAD_LETTER')";
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,at.toString());s.setString(2,id.toString());one(s,id,"timer cancellation");}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to cancel timer "+id,x);}
    }
    @Override public int releaseExpiredClaims(Instant now){
        String sql="update workflow_timer set status_value='RETRY_SCHEDULED',claimed_by=null,claim_until=null,updated_at=? where status_value='CLAIMED' and claim_until<=?";
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,now.toString());s.setString(2,now.toString());return s.executeUpdate();}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to release expired timer claims",x);}
    }
    @Override public void appendAttempt(WorkflowTimerAttempt a){String sql="insert into workflow_timer_attempt (id,timer_id,attempt_number,status_value,owner_id,error_message,created_at) values (?,?,?,?,?,?,?)";
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,a.attemptId().toString());s.setString(2,a.timerId().toString());s.setInt(3,a.attemptNumber());s.setString(4,a.status().name());s.setString(5,a.ownerId());s.setString(6,a.errorMessage());s.setString(7,a.createdAt().toString());one(s,a.timerId(),"timer attempt");}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to append timer attempt "+a.timerId(),x);}}
    @Override public List<WorkflowTimerAttempt> findAttempts(UUID id){String sql="select id,timer_id,attempt_number,status_value,owner_id,error_message,created_at from workflow_timer_attempt where timer_id=? order by attempt_number";
        try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimerAttempt> out=new ArrayList<>();while(r.next())out.add(new WorkflowTimerAttempt(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getInt(3),WorkflowTimerStatus.valueOf(r.getString(4)),r.getString(5),r.getString(6),Instant.parse(r.getString(7))));return List.copyOf(out);}}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load timer attempts "+id,x);}}

    private void guarded(UUID id,String owner,String sql,Instant at,String ignored){try(Connection c=connections.open();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,at.toString());s.setString(2,id.toString());s.setString(3,owner);one(s,id,"timer completion");}catch(SQLException x){throw new WorkflowInfrastructureException("Failed to complete timer "+id,x);}}
    private List<WorkflowTimer> queryEligible(Instant now,int limit){try(Connection c=connections.open()){return queryEligible(c,now,limit);}catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load due timers",x);}}
    private List<WorkflowTimer> queryEligible(Connection c,Instant now,int limit)throws SQLException{
        String sql="select "+columns()+" from workflow_timer where status_value in ('PENDING','RETRY_SCHEDULED') and coalesce(next_attempt_at,due_at)<=? and (claim_until is null or claim_until<=?) order by coalesce(next_attempt_at,due_at),created_at,id limit ?";
        try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,now.toString());s.setString(2,now.toString());s.setInt(3,limit);try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimer> out=new ArrayList<>();while(r.next())out.add(map(r));return out;}}
    }
    private static WorkflowTimer map(ResultSet r)throws SQLException{return new WorkflowTimer(UUID.fromString(r.getString("id")),WorkflowInstanceId.fromString(r.getString("workflow_instance_id")),r.getString("step_name"),Instant.parse(r.getString("due_at")),r.getString("target_node"),event(r.getString("emitted_event")),WorkflowTimerStatus.valueOf(r.getString("status_value")),r.getInt("attempt_count"),instant(r.getString("next_attempt_at")),r.getString("claimed_by"),instant(r.getString("claim_until")),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    private static void bind(PreparedStatement s,WorkflowTimer t)throws SQLException{s.setString(1,t.timerId().toString());s.setString(2,t.workflowInstanceId().toString());s.setString(3,"STEP_TIMEOUT");s.setString(4,t.dueAt().toString());s.setString(5,t.status().name());s.setString(6,t.createdAt().toString());s.setString(7,t.stepName());s.setString(8,t.targetNode());s.setString(9,t.emittedEvent()==null?null:t.emittedEvent().value());s.setInt(10,t.attemptCount());s.setString(11,t.nextAttemptAt()==null?null:t.nextAttemptAt().toString());s.setString(12,t.claimedBy());s.setString(13,t.claimUntil()==null?null:t.claimUntil().toString());s.setString(14,t.updatedAt().toString());}
    private static void one(PreparedStatement s,UUID id,String action)throws SQLException{if(s.executeUpdate()!=1)throw new PersistenceConstraintException("Guard rejected "+action+" for "+id);}
    private static void requireOwner(String owner,Instant until,Instant now,int limit){if(owner==null||owner.isBlank())throw new IllegalArgumentException("ownerId is required");if(until==null||!until.isAfter(now))throw new IllegalArgumentException("claimUntil must be after now");if(limit<1)throw new IllegalArgumentException("limit must be positive");}
    private static Instant instant(String v){return v==null?null:Instant.parse(v);} private static EventName event(String v){return v==null?null:new EventName(v);}
    private static String columns(){return "id,workflow_instance_id,due_at,status_value,step_name,target_node,emitted_event,attempt_count,next_attempt_at,claimed_by,claim_until,created_at,updated_at";}
}
