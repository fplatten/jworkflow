package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.inbox.*;
import org.jworkflow.persistence.InboxRepository;
import org.jworkflow.persistence.PersistenceConstraintException;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * JDBC first-arrival inbox deduplication, ordered claims and processing history. PostgreSQL claims use SKIP LOCKED
 * and acquisition tokens; SQLite serializes writes. Token finalization must share the transaction with dependent
 * workflow writes.
 */
final class JdbcInboxRepository implements InboxRepository {
    private static final String TEXT_SELECT_PREFIX = "select ";
    private static final String COLUMNS = "id,external_event_id,source_system,correlation_id,causation_id,received_at,processed_at,status_value,last_error_message,message_payload,message_payload_blob,message_content_type,message_schema_name,message_schema_version,message_metadata_json,message_redaction_status,attempt_count,next_attempt_at,claimed_by,claim_until,claim_token";
    private final JdbcConnectionFactory connections;
        private final JdbcEventMessageCodec messages=new JdbcEventMessageCodec();
    JdbcInboxRepository(JdbcConnectionFactory connections){this.connections=connections;
    }

    /**
     * {@inheritDoc}
     */
    @Override public InboxInsertResult insertIfAbsent(InboxMessage m){
        String sql=connections.strategy().insertIgnoringDuplicate("insert into workflow_inbox "+"""
                (id,external_event_id,source_system,correlation_id,causation_id,received_at,processed_at,status_value,
                last_error_message,message_payload,message_payload_blob,message_content_type,message_schema_name,message_schema_version,message_metadata_json,message_redaction_status,
                attempt_count,next_attempt_at,claimed_by,claim_until,claim_token) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ""","external_event_id,source_system");
        try(Connection c=connections.open();
            PreparedStatement s=c.prepareStatement(sql)){s.setString(1,m.messageId().toString());
            s.setString(2,m.externalEventId());
            s.setString(3,m.sourceSystem());
            s.setString(4,m.correlationId());
            s.setString(5,m.causationId());
            connections.strategy().bindInstant(s, 6, m.receivedAt());
            connections.strategy().bindInstant(s, 7, m.processedAt());
            s.setString(8,m.status().name());
            s.setString(9,m.lastError());
            messages.bind(s,10,m.message(),connections.strategy());
            s.setInt(17,m.attemptCount());
            connections.strategy().bindInstant(s, 18, m.nextAttemptAt());
            s.setString(19,m.claimedBy());
            connections.strategy().bindInstant(s, 20, m.claimUntil());
            s.setString(21,m.claimToken());
            int affected=connections.strategy().executeDuplicateInsert(connections,c,s,"workflow_inbox_pkey",()->
                    findByExternalIdentity(c,m.sourceSystem(),m.externalEventId())
                            .filter(winner->winner.messageId().equals(m.messageId())).isPresent());
            if(affected!=0&&affected!=1)throw new SQLException("Unexpected inbox insert count");
            boolean inserted=affected==1;
            InboxMessage actual=findByExternalIdentity(c,m.sourceSystem(),m.externalEventId())
                    .orElseThrow(()->new PersistenceConstraintException("Inbox duplicate winner is no longer available"));
            return new InboxInsertResult(actual,inserted);
        }
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to insert inbox message "+m.messageId(),x);
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public Optional<InboxMessage> findById(UUID id){
        String sql = TEXT_SELECT_PREFIX + COLUMNS + " from workflow_inbox where id=?";
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new WorkflowInfrastructureException("Failed to find inbox message " + id, exception);
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public Optional<InboxMessage> findByExternalIdentity(String source,String external){
        try(Connection c=connections.open()){return findByExternalIdentity(c,source,external);}
        catch(SQLException x){throw new WorkflowInfrastructureException("Failed to find inbox identity",x);}
    }
    private Optional<InboxMessage> findByExternalIdentity(Connection c,String source,String external)throws SQLException{
        String sql=TEXT_SELECT_PREFIX+COLUMNS+" from workflow_inbox where source_system=? and external_event_id=?";
        try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,source);
            s.setString(2,external);
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(map(r)):Optional.empty();
        }}
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<InboxMessage> claimEligible(Instant now,String owner,Instant until,int limit){
        return JdbcLeaseSupport.claim(connections,JdbcLeaseSupport.Queue.INBOX,COLUMNS,this::map,now,owner,until,limit);
    }
    /**
     * {@inheritDoc}
     */
    @Override public List<InboxMessage> claimEligibleFenced(Instant now,String owner,Instant until,int limit){return claimEligible(now,owner,until,limit);}
    /**
     * {@inheritDoc}
     */
    @Override public void requireClaim(UUID id,String owner,String token){JdbcLeaseSupport.requireClaim(connections,JdbcLeaseSupport.Queue.INBOX,id,owner,token);}

    /**
     * {@inheritDoc}
     */
    @Override public void markProcessed(UUID id,String owner,Instant at){connections.strategy().requireLegacyClaimSupport();transition(id,owner,"update workflow_inbox set status_value='PROCESSED',processed_at=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value='CLAIMED' and claimed_by=?",at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void scheduleRetry(UUID id,String owner,Instant next,String error){connections.strategy().requireLegacyClaimSupport();String sql="update workflow_inbox set status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, next);
        s.setString(2,error);
        s.setString(3,id.toString());
        s.setString(4,owner);
        one(s,id,"inbox retry");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed inbox retry "+id,x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public void markDeadLetter(UUID id,String owner,String error,Instant at){connections.strategy().requireLegacyClaimSupport();String sql="update workflow_inbox set status_value='DEAD_LETTER',attempt_count=attempt_count+1,dead_lettered_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value='CLAIMED' and claimed_by=?";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,error);
        s.setString(3,id.toString());
        s.setString(4,owner);
        one(s,id,"inbox dead-letter");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed inbox dead-letter "+id,x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public void markProcessed(UUID id,String owner,String token,Instant at){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.INBOX,id,owner,token,"status_value='PROCESSED',processed_at=?,claimed_by=null,claim_until=null,claim_token=null",at);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void scheduleRetry(UUID id,String owner,String token,Instant next,String error){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.INBOX,id,owner,token,"status_value='RETRY_SCHEDULED',attempt_count=attempt_count+1,next_attempt_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null",next,error);
    }
    /**
     * {@inheritDoc}
     */
    @Override public void markDeadLetter(UUID id,String owner,String token,String error,Instant at){
        JdbcLeaseSupport.transition(connections,JdbcLeaseSupport.Queue.INBOX,id,owner,token,"status_value='DEAD_LETTER',attempt_count=attempt_count+1,dead_lettered_at=?,last_error_message=?,claimed_by=null,claim_until=null,claim_token=null",at,error);
    }
    /**
     * {@inheritDoc}
     */
    @Override public int releaseExpiredClaims(Instant now){return JdbcLeaseSupport.release(connections,JdbcLeaseSupport.Queue.INBOX,now);}
    /**
     * {@inheritDoc}
     */
    @Override public void appendAttempt(InboxAttempt a){String sql="insert into workflow_inbox_attempt (id,inbox_id,attempt_number,status_value,error_code,error_message,created_at) values (?,?,?,?,?,?,?)";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,a.attemptId().toString());
        s.setString(2,a.messageId().toString());
        s.setInt(3,a.attemptNumber());
        s.setString(4,a.status().name());
        s.setString(5,a.errorCode());
        s.setString(6,a.errorMessage());
        connections.strategy().bindInstant(s, 7, a.createdAt());
        one(s,a.messageId(),"inbox attempt append");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed inbox attempt",x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public List<InboxAttempt> findAttempts(UUID id){String sql="select id,inbox_id,attempt_number,status_value,error_code,error_message,created_at from workflow_inbox_attempt where inbox_id=? order by attempt_number";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){s.setString(1,id.toString());
        try(ResultSet r=s.executeQuery()){ArrayList<InboxAttempt> out=new ArrayList<>();
        while(r.next())out.add(new InboxAttempt(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),r.getInt(3),InboxMessageStatus.valueOf(r.getString(4)),r.getString(5),r.getString(6),connections.strategy().readInstant(r, 7)));
        return List.copyOf(out);
    }}catch(SQLException x){throw new WorkflowInfrastructureException("Failed inbox attempts",x);
    }}
    /**
     * {@inheritDoc}
     */
    @Override public void requestReprocessing(UUID id,Instant at){String sql="update workflow_inbox set status_value='RECEIVED',next_attempt_at=?,claimed_by=null,claim_until=null,claim_token=null where id=? and status_value in ('DEAD_LETTER','RETRY_SCHEDULED')";
        try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,id.toString());
        one(s,id,"inbox reprocessing");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed inbox reprocessing "+id,x);
    }}
    private InboxMessage map(ResultSet r)throws SQLException{return new InboxMessage(UUID.fromString(r.getString("id")),r.getString("external_event_id"),r.getString("source_system"),messages.read(r),r.getString("correlation_id"),r.getString("causation_id"),connections.strategy().readInstant(r, "received_at"),connections.strategy().readInstant(r, "processed_at"),InboxMessageStatus.valueOf(r.getString("status_value")),r.getInt("attempt_count"),connections.strategy().readInstant(r, "next_attempt_at"),r.getString("last_error_message"),r.getString("claimed_by"),connections.strategy().readInstant(r, "claim_until"),r.getString("claim_token"));
    }
    private void transition(UUID id,String owner,String sql,Instant at){try(Connection c=connections.open();
        PreparedStatement s=c.prepareStatement(sql)){connections.strategy().bindInstant(s, 1, at);
        s.setString(2,id.toString());
        s.setString(3,owner);
        one(s,id,"inbox transition");
    }catch(SQLException x){throw new WorkflowInfrastructureException("Failed inbox transition "+id,x);
    }}
    private static void one(PreparedStatement s,UUID id,String action)throws SQLException{if(s.executeUpdate()!=1)throw new PersistenceConstraintException("Guard rejected "+action+" for "+id);
    }
    private static void validateClaim(Instant now,String owner,Instant until,int limit){if(owner==null||owner.isBlank()||until==null||!until.isAfter(now)||limit<1)throw new IllegalArgumentException("Invalid claim arguments");
    }
}
