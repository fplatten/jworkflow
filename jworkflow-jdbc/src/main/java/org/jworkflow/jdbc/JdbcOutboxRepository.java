package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.outbox.*;
import org.jworkflow.persistence.OutboxRepository;
import org.jworkflow.persistence.PersistenceConstraintException;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class JdbcOutboxRepository implements OutboxRepository {
    private static final String TEXT_SELECT_PREFIX = "select ";
    private static final String COLUMNS = "id,event_id,destination,idempotency_key,correlation_id,causation_id,created_at,published_at,status_value,last_error_message,message_payload,message_payload_blob,message_content_type,message_schema_name,message_schema_version,message_metadata_json,message_redaction_status,attempt_count,next_attempt_at,claimed_by,claim_until";
    private final JdbcConnectionFactory connections;
        private final JdbcEventMessageCodec messages=new JdbcEventMessageCodec();
    JdbcOutboxRepository(JdbcConnectionFactory connections){this.connections=connections;
    }
    @Override public OutboxMessage enqueue(OutboxMessage m){Optional<OutboxMessage> existing=findByIdempotencyKey(m.destination(),m.idempotencyKey());
        if(existing.isPresent()){if(!same(existing.get(),m))throw new PersistenceConstraintException("Outbox idempotency key contains different content");
        return existing.get();
    }
        String sql="insert into workflow_outbox (id,event_id,destination,idempotency_key,correlation_id,causation_id,created_at,published_at,status_value,retry_count,last_error_message,message_payload,message_payload_blob,message_content_type,message_schema_name,message_schema_version,message_metadata_json,message_redaction_status,attempt_count,next_attempt_at,claimed_by,claim_until) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,m.messageId().toString());
            s.setString(2,m.eventId().toString());
            s.setString(3,m.destination());
            s.setString(4,m.idempotencyKey());
            s.setString(5,m.correlationId());
            s.setString(6,m.causationId());
            s.setString(7,m.createdAt().toString());
            s.setString(8,text(m.publishedAt()));
            s.setString(9,m.status().name());
            s.setInt(10,m.attemptCount());
            s.setString(11,m.lastError());
            messages.bind(s,12,m.message());
            s.setInt(19,m.attemptCount());
            s.setString(20,text(m.nextAttemptAt()));
            s.setString(21,m.claimedBy());
            s.setString(22,text(m.claimUntil()));
            one(s,m.messageId(),"outbox enqueue");
            return m;
        }catch(SQLException x){Optional<OutboxMessage> raced=findByIdempotencyKey(m.destination(),m.idempotencyKey());
            if(raced.isPresent()&&same(raced.get(),m))return raced.get();
            throw new WorkflowInfrastructureException("Failed outbox enqueue "+m.messageId(),x);
        }}
    @Override public Optional<OutboxMessage> findById(UUID id){return find("id",id.toString(),null);
    }
    @Override public Optional<OutboxMessage> findByIdempotencyKey(String destination,String key){return find("destination",destination,key);
    }
    @Override public List<OutboxMessage> claimEligible(Instant now,String owner,Instant until,int limit){validate(now,owner,until,limit);
        ArrayList<OutboxMessage> out=new ArrayList<>();
        try(Connection c=connections.open()){String q=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_outbox where status_value in ('PENDING','RETRY_SCHEDULED') and (next_attempt_at is null or next_attempt_at<=?) and (claim_until is null or claim_until<=?) order by coalesce(next_attempt_at,created_at),created_at,id limit ?";
        try(PreparedStatement s=c.prepareStatement(q)){s.setString(1,now.toString());
        s.setString(2,now.toString());
        s.setInt(3,limit);
        try(ResultSet r=s.executeQuery()){while(r.next()){OutboxMessage m=map(r);
        try(PreparedStatement u=c.prepareStatement("update workflow_outbox set status_value='CLAIMED',claimed_by=?,claim_until=? where id=? and status_value in ('PENDING','RETRY_SCHEDULED') and (claim_until is null or claim_until<=?)")){u.setString(1,owner);
        u.setString(2,until.toString());
        u.setString(3,m.messageId().toString());
        u.setString(4,now.toString());
        if(u.executeUpdate()==1)out.add(new OutboxMessage(m.messageId(),m.eventId(),m.destination(),m.idempotencyKey(),m.message(),m.correlationId(),m.causationId(),m.createdAt(),m.publishedAt(),OutboxMessageStatus.CLAIMED,m.attemptCount(),m.nextAttemptAt(),m.lastError(),owner,until));
    }}}}return List.copyOf(out);
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox claim",x);
    }}
    @Override public void markPublished(UUID id,String owner,Instant at){transition(id,owner,"update workflow_outbox set status_value='PUBLISHED',attempt_count=attempt_count+1,published_at=?,claimed_by=null,claim_until=null where id=? and status_value='CLAIMED' and claimed_by=?",at);
    }
    @Override public void scheduleRetry(UUID id,String owner,Instant next,String error){String sql="update workflow_outbox set status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,retry_count=retry_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,next.toString());
        s.setString(2,error);
        s.setString(3,id.toString());
        s.setString(4,owner);
        one(s,id,"outbox retry");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox retry "+id,x);
    }}
    @Override public void markDeadLetter(UUID id,String owner,String error,Instant at){String sql="update workflow_outbox set status_value='DEAD_LETTER',attempt_count=attempt_count+1,retry_count=retry_count+1,dead_lettered_at=?,last_error_message=?,claimed_by=null,claim_until=null where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,at.toString());
        s.setString(2,error);
        s.setString(3,id.toString());
        s.setString(4,owner);
        one(s,id,"outbox dead-letter");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox dead-letter "+id,x);
    }}
    @Override public int releaseExpiredClaims(Instant now){try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement("update workflow_outbox set status_value='RETRY_SCHEDULED',claimed_by=null,claim_until=null where status_value='CLAIMED' and claim_until<=?")){s.setString(1,now.toString());
        return s.executeUpdate();
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed releasing outbox claims",x);
    }}
    @Override public void appendAttempt(OutboxAttempt a){String sql="insert into workflow_outbox_attempt (id,outbox_id,attempt_number,status_value,error_code,error_message,created_at) values (?,?,?,?,?,?,?)";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,a.attemptId().toString());
        s.setString(2,a.messageId().toString());
        s.setInt(3,a.attemptNumber());
        s.setString(4,a.status().name());
        s.setString(5,a.errorCode());
        s.setString(6,a.errorMessage());
        s.setString(7,a.createdAt().toString());
        one(s,a.messageId(),"outbox attempt");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox attempt",x);
    }}
    @Override public List<OutboxAttempt> findAttempts(UUID id){String sql="select id,outbox_id,attempt_number,status_value,error_code,error_message,created_at from workflow_outbox_attempt where outbox_id=? order by attempt_number";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,id.toString());
        try(ResultSet r=s.executeQuery()){ArrayList<OutboxAttempt> out=new ArrayList<>();
        while(r.next())out.add(new OutboxAttempt(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getInt(3),OutboxMessageStatus.valueOf(r.getString(4)),r.getString(5),r.getString(6),Instant.parse(r.getString(7))));
        return List.copyOf(out);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox attempts",x);
    }}
    @Override public void requestRepublishing(UUID id,Instant at){String sql="update workflow_outbox set status_value='PENDING',next_attempt_at=?,claimed_by=null,claim_until=null where id=? and status_value in ('DEAD_LETTER','RETRY_SCHEDULED')";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,at.toString());
        s.setString(2,id.toString());
        one(s,id,"outbox republishing");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox republishing "+id,x);
    }}
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
            s.setString(2,second);
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
    private OutboxMessage map(ResultSet r)throws SQLException{return new OutboxMessage(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("event_id")),r.getString("destination"),r.getString("idempotency_key"),messages.read(r),r.getString("correlation_id"),r.getString("causation_id"),Instant.parse(r.getString("created_at")),instant(r.getString("published_at")),OutboxMessageStatus.valueOf(r.getString("status_value")),r.getInt("attempt_count"),instant(r.getString("next_attempt_at")),r.getString("last_error_message"),r.getString("claimed_by"),instant(r.getString("claim_until")));
    }
    private void transition(UUID id,String owner,String sql,Instant at){try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,at.toString());
        s.setString(2,id.toString());
        s.setString(3,owner);
        one(s,id,"outbox transition");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed outbox transition "+id,x);
    }}
    private static boolean same(OutboxMessage a,OutboxMessage b){return a.eventId().equals(b.eventId())&&a.destination().equals(b.destination())&&a.idempotencyKey().equals(b.idempotencyKey())&&a.message().equals(b.message())&&Objects.equals(a.correlationId(),b.correlationId())&&Objects.equals(a.causationId(),b.causationId());
    }
    private static void one(PreparedStatement s,UUID id,String action)throws SQLException{if(s.executeUpdate()!=1)throw new PersistenceConstraintException("Guard rejected "+action+" for "+id);
    }
    private void validate(Instant now,String owner,Instant until,int limit){connections.requireImmediateTransaction("Outbox claiming");
        if(owner==null||owner.isBlank()||until==null||!until.isAfter(now)||limit<1)throw new IllegalArgumentException("Invalid claim arguments");
    }
    private static Instant instant(String v){return v==null?null:Instant.parse(v);
    }private static String text(Instant v){return v==null?null:v.toString();
    }
}
