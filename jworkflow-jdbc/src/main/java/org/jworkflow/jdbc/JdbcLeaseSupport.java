package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.persistence.StaleWorkflowClaimException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Shared mechanics for the three fixed durable queues, including transaction poisoning on a stale guard.
 * SQL structure is trusted implementation metadata: Queue supplies literal identifiers/predicates/orderings,
 * claim columns come only from the three repositories' COLUMNS constants, and transition assignments are
 * literal clauses at those same repository call sites. Neither fragment is a public configuration/input API.
 * Keep all runtime values in JDBC parameters; never pass application input as columns or assignments.
 */
final class JdbcLeaseSupport {
    /**
     * Fixed table, status and ordering metadata for timer, inbox and outbox claim SQL.
     */
    enum Queue {
        /**
         * A durable timer schedule.
         */
        TIMER("workflow_timer","'PENDING','RETRY_SCHEDULED'","coalesce(next_attempt_at,due_at)<=?","coalesce(next_attempt_at,due_at),created_at,id",true),
        /**
         * A durable incoming message.
         */
        INBOX("workflow_inbox","'RECEIVED','RETRY_SCHEDULED'","(next_attempt_at is null or next_attempt_at<=?)","coalesce(next_attempt_at,received_at),received_at,id",false),
        /**
         * A durable outgoing publication.
         */
        OUTBOX("workflow_outbox","'PENDING','RETRY_SCHEDULED'","(next_attempt_at is null or next_attempt_at<=?)","coalesce(next_attempt_at,created_at),created_at,id",false);
        final String table;
        final String ready;
        final String deadline;
        final String order;
        final boolean timer;
        Queue(String table,String ready,String deadline,String order,boolean timer){this.table=table;this.ready=ready;this.deadline=deadline;this.order=order;this.timer=timer;}
        String eligible(){return "("+deadline+" and ((status_value in ("+ready+") and (claim_until is null or claim_until<=?)) or (status_value='CLAIMED' and claim_until<=?)))";}
    }
    /**
     * Maps claimed JDBC rows back to the corresponding immutable queue value.
     * @param <T> the value type
     */
    @FunctionalInterface interface Mapper<T>{

        /**
         * Maps the current result row to the queue value without advancing or closing the result set.
         * @param rows JDBC result positioned at the row to decode
         * @return the value produced by the work
         * @throws SQLException if the database operation fails
         */
        T map(ResultSet rows)throws SQLException;}
    private JdbcLeaseSupport(){ }

    /**
     * Runtime lease acquisition values, validated by the claim boundary before database work.
     * Construction alone performs no validation. These values are bound parameters, never SQL fragments.
     * @param now eligibility and lease-expiry comparison time, required by the claim boundary
     * @param owner nonblank worker identity stored literally on the claimed rows
     * @param until lease expiration, strictly after now
     * @param limit positive maximum number of rows to claim
     */
    record ClaimRequest(Instant now, String owner, Instant until, int limit) { }

    static <T> List<T> claim(JdbcConnectionFactory factory,Queue queue,String columns,Mapper<T> mapper,ClaimRequest request){
        Instant now = request.now();
        String owner = request.owner();
        Instant until = request.until();
        int limit = request.limit();
        factory.requireWriteTransaction("Lease acquisition");
        Objects.requireNonNull(now,"now");
        if(owner==null||owner.isBlank()||until==null||!until.isAfter(now)||limit<1)throw new IllegalArgumentException("Invalid lease acquisition arguments");
        var strategy=factory.strategy();
        try(Connection connection=factory.open()){
            String postgres=strategy.claimBatchSql(queue,columns);
            if(postgres!=null)try(PreparedStatement statement=connection.prepareStatement(postgres)){
                eligibility(strategy,statement,now,limit);statement.setString(5,owner);strategy.bindInstant(statement,6,until);
                if(queue.timer)strategy.bindInstant(statement,7,now);
                return rows(statement,mapper);
            }
            List<String> ids=new ArrayList<>();
            // Only fixed Queue metadata is concatenated; eligibility timestamps and limit are bound below.
            try(PreparedStatement statement=connection.prepareStatement("select id from "+queue.table+" where "+queue.eligible()+" order by "+queue.order+" limit ?")){
                eligibility(strategy,statement,now,limit);
                try(ResultSet result=statement.executeQuery()){while(result.next())ids.add(result.getString(1));}
            }
            List<T> result=new ArrayList<>();
            for(String id:ids){
                // The enum chooses SQL structure. Even an ID read from storage is bound, never interpolated.
                String sql="update "+queue.table+" set status_value='CLAIMED',claimed_by=?,claim_until=?,claim_token=?"+(queue.timer?",updated_at=?":"")+" where id=?";
                try(PreparedStatement statement=connection.prepareStatement(sql)){
                    statement.setString(1,owner);strategy.bindInstant(statement,2,until);statement.setString(3,UUID.randomUUID().toString());
                    int index=4;
                    if(queue.timer) { strategy.bindInstant(statement,index++,now); }
                    statement.setString(index,id);
                    if(statement.executeUpdate()!=1)throw stale(factory);
                }
                // columns is the caller repository's private static final COLUMNS literal, not request data.
                try(PreparedStatement statement=connection.prepareStatement("select "+columns+" from "+queue.table+" where id=?")){
                    statement.setString(1,id);result.addAll(rows(statement,mapper));
                }
            }
            return List.copyOf(result);
        }catch(SQLException failure){throw new WorkflowInfrastructureException("Failed to acquire durable leases",failure);}
    }
    private static void eligibility(JdbcDatabaseStrategy strategy,PreparedStatement statement,Instant now,int limit)throws SQLException{
        for(int i=1;i<=3;i++) { strategy.bindInstant(statement,i,now); }
        statement.setInt(4,limit);
    }
    private static <T> List<T> rows(PreparedStatement statement,Mapper<T> mapper)throws SQLException{
        List<T> result=new ArrayList<>();try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(mapper.map(rows));}return List.copyOf(result);
    }

    static void requireClaim(JdbcConnectionFactory factory,Queue queue,UUID id,String owner,String token){
        if(factory.currentTransactionConnection()==null)throw new IllegalStateException("Lease validation requires an enclosing adapter transaction");
        transition(factory,queue,id,owner,token,"claim_token=claim_token");
    }
    static void transition(JdbcConnectionFactory factory,Queue queue,UUID id,String owner,String token,String assignments,Object... values){
        if(owner==null||owner.isBlank()||token==null||token.isBlank())throw stale(factory);
        // assignments is a repository literal (or the fixed requireClaim no-op); values and guards are bound.
        String sql="update "+queue.table+" set "+assignments+" where id=? and status_value='CLAIMED' and claimed_by=? and claim_token=?";
        try(Connection connection=factory.open();PreparedStatement statement=connection.prepareStatement(sql)){
            int index=1;for(Object value:values){bind(factory.strategy(),statement,index++,value);}
            statement.setString(index++,id.toString());statement.setString(index++,owner);statement.setString(index,token);
            if(statement.executeUpdate()!=1)throw stale(factory);
        }catch(SQLException failure){throw new WorkflowInfrastructureException("Failed fenced lease transition",failure);}
    }
    static int release(JdbcConnectionFactory factory,Queue queue,Instant now){
        try(Connection connection=factory.open();PreparedStatement statement=connection.prepareStatement(factory.strategy().releaseClaimsSql(queue))){
            factory.strategy().bindInstant(statement,1,now);
            if(queue.timer)factory.strategy().bindInstant(statement,2,now);
            return statement.executeUpdate();
        }catch(SQLException failure){throw new WorkflowInfrastructureException("Failed expired lease release",failure);}
    }
    static StaleWorkflowClaimException stale(JdbcConnectionFactory factory){
        var failure=new StaleWorkflowClaimException();factory.markRollbackOnly(failure);return failure;
    }
    private static void bind(JdbcDatabaseStrategy strategy,PreparedStatement statement,int index,Object value)throws SQLException{
        if(value instanceof Instant time)strategy.bindInstant(statement,index,time);
        else if(value==null)statement.setNull(index,Types.NULL);
        else statement.setString(index,value.toString());
    }
}
