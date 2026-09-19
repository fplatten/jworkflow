package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.events.EventName;
import org.jworkflow.model.*;
import org.jworkflow.persistence.PersistenceConstraintException;
import org.jworkflow.persistence.WorkflowTimerRepository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * JDBC timer scheduling, ordered lease claims and immutable attempts. Fenced transitions prevent stale
 * acquisitions from committing workflow or history changes.
 */
final class JdbcWorkflowTimerRepository implements WorkflowTimerRepository {
    private static final String COLUMNS = "id,workflow_instance_id,due_at,status_value,step_name,target_node,emitted_event,attempt_count,next_attempt_at,claimed_by,claim_until,created_at,updated_at,claim_token";
    private static final String TEXT_SELECT_PREFIX = "select ";
    private final JdbcConnectionFactory connections;
    JdbcWorkflowTimerRepository(JdbcConnectionFactory connections){this.connections=connections;
    }

    /**
     * {@inheritDoc}
     */
    @Override public void save(WorkflowTimer timer){
        String sql="""
                insert into workflow_timer (id,workflow_instance_id,timer_type,due_at,status_value,created_at,step_name,target_node,emitted_event,
                attempt_count,next_attempt_at,claimed_by,claim_until,updated_at,claim_token) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set due_at=excluded.due_at,status_value=excluded.status_value,step_name=excluded.step_name,
                target_node=excluded.target_node,emitted_event=excluded.emitted_event,attempt_count=excluded.attempt_count,
                next_attempt_at=excluded.next_attempt_at,claimed_by=excluded.claimed_by,claim_until=excluded.claim_until,updated_at=excluded.updated_at,claim_token=excluded.claim_token
                where (workflow_timer.status_value<>'CLAIMED' and excluded.claim_token is null)
                   or (workflow_timer.status_value='CLAIMED' and workflow_timer.claim_token=excluded.claim_token and excluded.status_value='CLAIMED')
                """;
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){bind(s,timer);
            if(s.executeUpdate()!=1)throw JdbcLeaseSupport.stale(connections);
        }
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to save timer "+timer.timerId(),x);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public List<WorkflowTimer> dueTimers(Instant now){return queryEligible(now,Integer.MAX_VALUE);
    }

    /**
     * {@inheritDoc}
     */
    @Override public List<WorkflowTimer> findByWorkflowInstance(WorkflowInstanceId instanceId){
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_timer where workflow_instance_id=? order by created_at,id";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,instanceId.toString());
            try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimer> out=new ArrayList<>();
            while(r.next())out.add(map(r));
            return List.copyOf(out);
        }}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load timers for "+instanceId,x);
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<WorkflowTimer> findPending(int limit){if(limit<1)throw new IllegalArgumentException("limit must be positive");
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_timer where status_value in ('PENDING','CLAIMED','RETRY_SCHEDULED') order by coalesce(next_attempt_at,due_at),created_at,id limit ?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,limit);
        try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimer> out=new ArrayList<>();
        while(r.next())out.add(map(r));
        return List.copyOf(out);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed pending timer query",x);
    }}

    /**
     * {@inheritDoc}
     */
    @Override public List<WorkflowTimer> claimDue(Instant now,String owner,Instant until,int limit){
        return JdbcLeaseSupport.claim(connections,JdbcLeaseSupport.Queue.TIMER,COLUMNS,this::map,now,owner,until,limit);
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<WorkflowTimer> claimDueFenced(Instant now,String owner,Instant until,int limit){return claimDue(now,owner,until,limit);}
    /**
     * {@inheritDoc}
     */
    @Override public void requireClaim(UUID id,String owner,String token){JdbcLeaseSupport.requireClaim(connections,JdbcLeaseSupport.Queue.TIMER,id,owner,token);}

    /**
     * {@inheritDoc}
     */
    @Override public void markFired(UUID id,String owner,Instant at){connections.strategy().requireLegacyClaimSupport();guarded(id,owner,"update workflow_timer set status_value='FIRED',claimed_by=null,claim_until=null,claim_token=null,updated_at=? where id=? and status_value='CLAIMED' and claimed_by=?",at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void markFailed(UUID id,String owner,String error,Instant next){connections.strategy().requireLegacyClaimSupport();
        String sql="update workflow_timer set status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null,updated_at=? where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, next);
            s.setString(2,error);
            connections.strategy().bindInstant(s, 3, Instant.now());
            s.setString(4,id.toString());
            s.setString(5,owner);
            one(s,id,"timer failure");
        }
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to schedule timer retry "+id,x);
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public void cancel(UUID id,Instant at){
        String sql="update workflow_timer set status_value='CANCELED',claimed_by=null,claim_until=null,claim_token=null,updated_at=? where id=? and status_value not in ('FIRED','CANCELED','DEAD_LETTER')";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
            s.setString(2,id.toString());
            one(s,id,"timer cancellation");
        }
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to cancel timer "+id,x);
        }
    }

    /** Quarantines a failed acquisition whose handler must not be replayed automatically. */
    void markDeadLetter(UUID id,String owner,String error,Instant at) {connections.strategy().requireLegacyClaimSupport();
        String sql="update workflow_timer set status_value='DEAD_LETTER',attempt_count=attempt_count+1,next_attempt_at=null,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null,updated_at=? where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection connection=connections.open(); PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,error); connections.strategy().bindInstant(statement,2,at);
            statement.setString(3,id.toString()); statement.setString(4,owner); one(statement,id,"timer dead letter");
        } catch(SQLException failure) { throw new WorkflowInfrastructureException("Failed to quarantine timer",failure); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public void markFired(UUID id,String owner,String token,Instant at){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.TIMER,id,owner,token,"status_value='FIRED',claimed_by=null,claim_until=null,claim_token=null,updated_at=?",at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void markFailed(UUID id,String owner,String token,String error,Instant next){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.TIMER,id,owner,token,"status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null,updated_at=?",next,error,Instant.now());
    }
    /**
     * {@inheritDoc}
     */
    @Override public void markDeadLetter(UUID id,String owner,String token,String error,Instant at){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.TIMER,id,owner,token,"status_value='DEAD_LETTER',attempt_count=attempt_count+1,next_attempt_at=null,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null,updated_at=?",error,at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public int releaseExpiredClaims(Instant now){return JdbcLeaseSupport.release(connections,JdbcLeaseSupport.Queue.TIMER,now);}
    /**
     * {@inheritDoc}
     */
    @Override public void appendAttempt(WorkflowTimerAttempt a){String sql="insert into workflow_timer_attempt (id,timer_id,attempt_number,status_value,owner_id,error_message,created_at) values (?,?,?,?,?,?,?)";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,a.attemptId().toString());
            s.setString(2,a.timerId().toString());
            s.setInt(3,a.attemptNumber());
            s.setString(4,a.status().name());
            s.setString(5,a.ownerId());
            s.setString(6,a.errorMessage());
            connections.strategy().bindInstant(s, 7, a.createdAt());
            one(s,a.timerId(),"timer attempt");
        }
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to append timer attempt "+a.timerId(),x);
        }}
    /**
     * {@inheritDoc}
     */
    @Override public List<WorkflowTimerAttempt> findAttempts(UUID id){String sql="select id,timer_id,attempt_number,status_value,owner_id,error_message,created_at from workflow_timer_attempt where timer_id=? order by attempt_number";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,id.toString());
            try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimerAttempt> out=new ArrayList<>();
            while(r.next())out.add(new WorkflowTimerAttempt(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getInt(3),WorkflowTimerStatus.valueOf(r.getString(4)),r.getString(5),r.getString(6),connections.strategy().readInstant(r, 7)));
            return List.copyOf(out);
        }}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load timer attempts "+id,x);
        }}

    private void guarded(UUID id,String owner,String sql,Instant at){try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,id.toString());
        s.setString(3,owner);
        one(s,id,"timer completion");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed to complete timer "+id,x);
    }}
    private List<WorkflowTimer> queryEligible(Instant now,int limit){try(Connection c=connections.open()){return queryEligible(c,now,limit);
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed to load due timers",x);
    }}
    private List<WorkflowTimer> queryEligible(Connection c,Instant now,int limit)throws SQLException{
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_timer where status_value in ('PENDING','RETRY_SCHEDULED') and coalesce(next_attempt_at,due_at)<=? and (claim_until is null or claim_until<=?) order by coalesce(next_attempt_at,due_at),created_at,id limit ?";
        try(PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, now);
            connections.strategy().bindInstant(s, 2, now);
            s.setInt(3,limit);
            try(ResultSet r=s.executeQuery()){ArrayList<WorkflowTimer> out=new ArrayList<>();
            while(r.next())out.add(map(r));
            return out;
        }}
    }
    private WorkflowTimer map(ResultSet r)throws SQLException{return new WorkflowTimer(UUID.fromString(r.getString("id")),WorkflowInstanceId.fromString(r.getString("workflow_instance_id")),r.getString("step_name"),connections.strategy().readInstant(r, "due_at"),r.getString("target_node"),event(r.getString("emitted_event")),WorkflowTimerStatus.valueOf(r.getString("status_value")),r.getInt("attempt_count"),connections.strategy().readInstant(r, "next_attempt_at"),r.getString("claimed_by"),connections.strategy().readInstant(r, "claim_until"),connections.strategy().readInstant(r, "created_at"),connections.strategy().readInstant(r, "updated_at"),r.getString("claim_token"));
    }
    private void bind(PreparedStatement s,WorkflowTimer t)throws SQLException{s.setString(1,t.timerId().toString());
        s.setString(2,t.workflowInstanceId().toString());
        s.setString(3,"STEP_TIMEOUT");
        connections.strategy().bindInstant(s, 4, t.dueAt());
        s.setString(5,t.status().name());
        connections.strategy().bindInstant(s, 6, t.createdAt());
        s.setString(7,t.stepName());
        s.setString(8,t.targetNode());
        s.setString(9,t.emittedEvent()==null?null:t.emittedEvent().value());
        s.setInt(10,t.attemptCount());
        connections.strategy().bindInstant(s, 11, t.nextAttemptAt());
        s.setString(12,t.claimedBy());
        connections.strategy().bindInstant(s, 13, t.claimUntil());
        connections.strategy().bindInstant(s, 14, t.updatedAt());
        s.setString(15,t.claimToken());
    }
    private static void one(PreparedStatement s,UUID id,String action)throws SQLException{if(s.executeUpdate()!=1)throw new PersistenceConstraintException("Guard rejected "+action+" for "+id);
    }
    private static void requireOwner(String owner,Instant until,Instant now,int limit){if(owner==null||owner.isBlank())throw new IllegalArgumentException("ownerId is required");
        if(until==null||!until.isAfter(now))throw new IllegalArgumentException("claimUntil must be after now");
        if(limit<1)throw new IllegalArgumentException("limit must be positive");
    }
    private static EventName event(String v){return v==null?null:new EventName(v);
    }
}
