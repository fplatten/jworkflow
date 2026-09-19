package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.outbox.*;
import org.jworkflow.persistence.OutboxRepository;
import org.jworkflow.persistence.PersistenceConstraintException;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * JDBC publication-intent and lease repository. Duplicate destination/key pairs validate immutable content. Fenced
 * finalization protects database history after external publication but cannot prevent network duplicates.
 */
final class JdbcOutboxRepository implements OutboxRepository {
    private static final String TEXT_SELECT_PREFIX = "select ";
    private static final String COLUMNS = "id,event_id,destination,idempotency_key,correlation_id,causation_id,created_at,published_at,status_value,last_error_message,message_payload,message_payload_blob,message_content_type,message_schema_name,message_schema_version,message_metadata_json,message_redaction_status,attempt_count,next_attempt_at,claimed_by,claim_until,claim_token";
    private final JdbcConnectionFactory connections;
        private final JdbcEventMessageCodec messages=new JdbcEventMessageCodec();
    JdbcOutboxRepository(JdbcConnectionFactory connections){this.connections=connections;
    }
    /**
     * {@inheritDoc}
     */
    @Override public OutboxMessage enqueue(OutboxMessage m){
        String sql="insert into workflow_outbox (id,event_id,destination,idempotency_key,correlation_id,causation_id,created_at,published_at,status_value,retry_count,last_error_message,message_payload,message_payload_blob,message_content_type,message_schema_name,message_schema_version,message_metadata_json,message_redaction_status,attempt_count,next_attempt_at,claimed_by,claim_until,claim_token) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        sql=connections.strategy().insertIgnoringDuplicate(sql,"destination,idempotency_key");
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,m.messageId().toString());
            s.setString(2,m.eventId().toString());
            s.setString(3,m.destination());
            s.setString(4,connections.strategy().encodeIdempotencyKey(m.idempotencyKey()));
            s.setString(5,m.correlationId());
            s.setString(6,m.causationId());
            connections.strategy().bindInstant(s, 7, m.createdAt());
            connections.strategy().bindInstant(s, 8, m.publishedAt());
            s.setString(9,m.status().name());
            s.setInt(10,m.attemptCount());
            s.setString(11,m.lastError());
            messages.bind(s,12,m.message(),connections.strategy());
            s.setInt(19,m.attemptCount());
            connections.strategy().bindInstant(s, 20, m.nextAttemptAt());
            s.setString(21,m.claimedBy());
            connections.strategy().bindInstant(s, 22, m.claimUntil());
            s.setString(23,m.claimToken());
            int affected=connections.strategy().executeDuplicateInsert(connections,c,s,"workflow_outbox_pkey",()->
                    findByIdempotencyKey(c,m.destination(),m.idempotencyKey())
                            .filter(winner->winner.messageId().equals(m.messageId())).isPresent());
            if(affected==1)return m;
            if(affected!=0)throw new SQLException("Unexpected outbox insert count");
            OutboxMessage winner=findByIdempotencyKey(c,m.destination(),m.idempotencyKey())
                    .orElseThrow(()->new PersistenceConstraintException("Outbox duplicate winner is no longer available"));
            if(!same(winner,m))throw new PersistenceConstraintException("Outbox idempotency key contains different content");
            return winner;
        }catch(SQLException x){
            throw new WorkflowInfrastructureException("Failed outbox enqueue "+m.messageId(),x);
        }}
    /**
     * {@inheritDoc}
     */
    @Override public Optional<OutboxMessage> findById(UUID id){return find("id",id.toString(),null);
    }
    /**
     * {@inheritDoc}
     */
    @Override public Optional<OutboxMessage> findByIdempotencyKey(String destination,String key){return find("destination",destination,key);
    }
    private Optional<OutboxMessage> findByIdempotencyKey(Connection c,String destination,String key)throws SQLException{
        try(PreparedStatement statement=c.prepareStatement(TEXT_SELECT_PREFIX+COLUMNS+" from workflow_outbox where destination=? and idempotency_key=?")){
            statement.setString(1,destination);statement.setString(2,connections.strategy().encodeIdempotencyKey(key));
            try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(map(rows)):Optional.empty();}
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<OutboxMessage> claimEligible(Instant now,String owner,Instant until,int limit){
        return JdbcLeaseSupport.claim(connections,JdbcLeaseSupport.Queue.OUTBOX,COLUMNS,this::map,now,owner,until,limit);
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<OutboxMessage> claimEligibleFenced(Instant now,String owner,Instant until,int limit){return claimEligible(now,owner,until,limit);}
    /**
     * {@inheritDoc}
     */
    @Override public void requireClaim(UUID id,String owner,String token){JdbcLeaseSupport.requireClaim(connections,JdbcLeaseSupport.Queue.OUTBOX,id,owner,token);}

    /**
     * {@inheritDoc}
     */
    @Override public void markPublished(UUID id,String owner,Instant at){connections.strategy().requireLegacyClaimSupport();transition(id,owner,"update workflow_outbox set status_value='PUBLISHED',attempt_count=attempt_count+1,published_at=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value='CLAIMED' and claimed_by=?",at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void scheduleRetry(UUID id,String owner,Instant next,String error){connections.strategy().requireLegacyClaimSupport();String sql="update workflow_outbox set status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,retry_count=retry_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, next);
        s.setString(2,error);
        s.setString(3,id.toString());
        s.setString(4,owner);
        one(s,id,"outbox retry");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox retry "+id,x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public void markDeadLetter(UUID id,String owner,String error,Instant at){connections.strategy().requireLegacyClaimSupport();String sql="update workflow_outbox set status_value='DEAD_LETTER',attempt_count=attempt_count+1,retry_count=retry_count+1,dead_lettered_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,error);
        s.setString(3,id.toString());
        s.setString(4,owner);
        one(s,id,"outbox dead-letter");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox dead-letter "+id,x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public void markPublished(UUID id,String owner,String token,Instant at){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.OUTBOX,id,owner,token,"status_value='PUBLISHED',attempt_count=attempt_count+1,published_at=?,claimed_by=null,claim_until=null,claim_token=null",at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void scheduleRetry(UUID id,String owner,String token,Instant next,String error){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.OUTBOX,id,owner,token,"status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,retry_count=retry_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null",next,error);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void markDeadLetter(UUID id,String owner,String token,String error,Instant at){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.OUTBOX,id,owner,token,"status_value='DEAD_LETTER',attempt_count=attempt_count+1,retry_count=retry_count+1,dead_lettered_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null",at,error);
    }
    /**
     * {@inheritDoc}
     */
    @Override public int releaseExpiredClaims(Instant now){return JdbcLeaseSupport.release(connections,JdbcLeaseSupport.Queue.OUTBOX,now);}
    /**
     * {@inheritDoc}
     */
    @Override public void appendAttempt(OutboxAttempt a){String sql="insert into workflow_outbox_attempt (id,outbox_id,attempt_number,status_value,error_code,error_message,created_at) values (?,?,?,?,?,?,?)";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,a.attemptId().toString());
        s.setString(2,a.messageId().toString());
        s.setInt(3,a.attemptNumber());
        s.setString(4,a.status().name());
        s.setString(5,a.errorCode());
        s.setString(6,a.errorMessage());
        connections.strategy().bindInstant(s, 7, a.createdAt());
        one(s,a.messageId(),"outbox attempt");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox attempt",x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public List<OutboxAttempt> findAttempts(UUID id){String sql="select id,outbox_id,attempt_number,status_value,error_code,error_message,created_at from workflow_outbox_attempt where outbox_id=? order by attempt_number";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,id.toString());
        try(ResultSet r=s.executeQuery()){ArrayList<OutboxAttempt> out=new ArrayList<>();
        while(r.next())out.add(new OutboxAttempt(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getInt(3),OutboxMessageStatus.valueOf(r.getString(4)),r.getString(5),r.getString(6),connections.strategy().readInstant(r, 7)));
        return List.copyOf(out);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox attempts",x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public void requestRepublishing(UUID id,Instant at){String sql="update workflow_outbox set status_value='PENDING',next_attempt_at=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value in ('DEAD_LETTER','RETRY_SCHEDULED')";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,id.toString());
        one(s,id,"outbox republishing");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox republishing "+id,x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public List<OutboxMessage> findPending(int limit){if(limit<1)throw new IllegalArgumentException("limit must be positive");
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_outbox where status_value in ('PENDING','CLAIMED','RETRY_SCHEDULED','DEAD_LETTER') order by coalesce(next_attempt_at,created_at),created_at,id limit ?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,limit);
        try(ResultSet r=s.executeQuery()){ArrayList<OutboxMessage> out=new ArrayList<>();
        while(r.next())out.add(map(r));
        return List.copyOf(out);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed pending outbox query",x);
    }}
    private Optional<OutboxMessage> find(String column,String first,String second){
        if(second==null)return findBySingleColumn(column,first);
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_outbox where destination=? and idempotency_key=?";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,first);
            s.setString(2,connections.strategy().encodeIdempotencyKey(second));
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();
        }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox lookup",x);
        }
    }
    private Optional<OutboxMessage> findBySingleColumn(String column,String value){
        if(!"id".equals(column))throw new IllegalArgumentException("Unsupported outbox lookup column: "+column);
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_outbox where id=?";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,value);
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();
        }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox lookup",x);
        }
    }
    private OutboxMessage map(ResultSet r)throws SQLException{return new OutboxMessage(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("event_id")),r.getString("destination"),connections.strategy().decodeIdempotencyKey(r.getString("idempotency_key")),messages.read(r),r.getString("correlation_id"),r.getString("causation_id"),connections.strategy().readInstant(r, "created_at"),connections.strategy().readInstant(r, "published_at"),OutboxMessageStatus.valueOf(r.getString("status_value")),r.getInt("attempt_count"),connections.strategy().readInstant(r, "next_attempt_at"),r.getString("last_error_message"),r.getString("claimed_by"),connections.strategy().readInstant(r, "claim_until"),r.getString("claim_token"));
    }
    private void transition(UUID id,String owner,String sql,Instant at){try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,id.toString());
        s.setString(3,owner);
        one(s,id,"outbox transition");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox transition "+id,x);
    }}
    private boolean same(OutboxMessage a,OutboxMessage b){return a.eventId().equals(b.eventId())&&a.destination().equals(b.destination())&&a.idempotencyKey().equals(b.idempotencyKey())&&messages.sameContent(a.message(),b.message())&&Objects.equals(a.correlationId(),b.correlationId())&&Objects.equals(a.causationId(),b.causationId());
    }
    private static void one(PreparedStatement s,UUID id,String action)throws SQLException{if(s.executeUpdate()!=1)throw new PersistenceConstraintException("Guard rejected "+action+" for "+id);
    }
    private void validate(Instant now,String owner,Instant until,int limit){connections.requireWriteTransaction("Outbox claiming");
        if(owner==null||owner.isBlank()||until==null||!until.isAfter(now)||limit<1)throw new IllegalArgumentException("Invalid claim arguments");
    }
}
