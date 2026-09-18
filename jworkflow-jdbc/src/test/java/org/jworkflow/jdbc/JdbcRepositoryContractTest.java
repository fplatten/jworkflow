package org.jworkflow.jdbc;

import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.outbox.*;
import org.jworkflow.persistence.*;
import org.sqlite.JDBC;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

public final class JdbcRepositoryContractTest {
    public static void main(String[] args) throws Exception {
        definitionRoundTripAndConflicts();
        instanceRoundTripAndOptimisticConflict();
        eventAndStatusHistoryIsAppendOnlyAndOrdered();
        concurrentStatusAttemptsAreUniqueAndAppendOnly();
        timerStateMachineAndClaims();
        inboxDeduplicationAndStateMachine();
        outboxIdempotencyAndStateMachine();
        commandResultIdempotency();
        multiRepositoryRollbackIsAtomic();
    }

    private static void definitionRoundTripAndConflicts() throws Exception {
        Fixture f=fixture("definitions"); WorkflowDefinition definition=new WorkflowDefinition("orders","1","done",Map.of("done",WorkflowNode.end("done")),Map.of("source","orders.groovy"),"workflow('orders') { version '1' }");
        f.persistence.definitions().save(definition); WorkflowDefinition loaded=f.persistence.definitions().findRevision("orders","1",definition.revision()).orElseThrow();
        check(loaded.equals(definition),"definition must round trip including original source");check(f.persistence.definitions().findAll().size()==1,"definition recovery enumeration must include revision");
        try(Connection c=DriverManager.getConnection(f.url);Statement s=c.createStatement()){s.executeUpdate("update workflow_definition set canonical_json='{}' where workflow_key='orders'");}
        expect(PersistenceConstraintException.class,()->f.persistence.definitions().save(definition));
        try(Connection c=DriverManager.getConnection(f.url);Statement s=c.createStatement()){s.executeUpdate("update workflow_definition set canonical_json='"+escaped(new JdbcDefinitionCodec().write(definition))+"',checksum='bad' where workflow_key='orders'");}
        expect(PersistenceSerializationException.class,()->f.persistence.definitions().find("orders","1"));
    }

    private static void instanceRoundTripAndOptimisticConflict() throws Exception {
        Fixture f=fixture("instances");Instant now=Instant.parse("2026-01-01T00:00:00Z");Map<String,Object> variables=new LinkedHashMap<>();variables.put("nested",Arrays.asList(Map.of("amount",new BigDecimal("12.50")),null));variables.put("nullable",null);
        WorkflowSnapshot initial=new WorkflowSnapshot(WorkflowInstanceId.random(),"orders","1","rev-1","o-1","corr-1","waiting",WorkflowStatus.WAITING,variables,0,now,now);
        f.persistence.instances().insert(initial);WorkflowSnapshot a=f.persistence.instances().findById(initial.instanceId()).orElseThrow();WorkflowSnapshot b=f.second.instances().findById(initial.instanceId()).orElseThrow();
        check(((List<?>)a.variables().get("nested")).size()==2&&a.variables().containsKey("nullable"),"nested variables and null must round trip");
        f.persistence.instances().update(a.withLockVersion(1),0);expect(WorkflowOptimisticLockException.class,()->f.second.instances().update(b.withLockVersion(1),0));
        check(f.persistence.instances().findByCorrelationId("corr-1").isPresent(),"correlation lookup must recover instance");check(f.persistence.instances().findActive(10).size()==1,"active query must recover waiting instance");
    }

    private static void eventAndStatusHistoryIsAppendOnlyAndOrdered() throws Exception {
        Fixture f=fixture("events");WorkflowInstanceId id=WorkflowInstanceId.random();Instant now=Instant.parse("2026-01-01T00:00:00Z");
        WorkflowEvent first=event(UUID.randomUUID(),id,"order.created","corr","cause-0",Map.of("items",List.of(1,2)),now);
        WorkflowEvent second=event(UUID.randomUUID(),id,"payment.received","corr","cause-1",new byte[]{1,2,3},now.plusSeconds(1));
        f.persistence.transactions().execute(()->{f.persistence.events().append(first);f.persistence.events().append(second);});
        List<WorkflowEvent> history=f.persistence.events().findByWorkflowInstance(id);check(history.size()==2&&history.get(0).metadata().eventId().equals(first.metadata().eventId()),"events must retain append order");
        WorkflowEvent binary=f.persistence.events().find(second.metadata().eventId()).orElseThrow();check(binary.message().payload() instanceof byte[] bytes&&bytes.length==3,"binary event payload must round trip");check("cause-1".equals(binary.metadata().causationId())&&"schema-1".equals(binary.message().schemaVersion()),"event metadata must round trip");
        EventStatusAttempt one=attempt(first,1,EventStatusValue.FAILED);EventStatusAttempt two=attempt(first,2,EventStatusValue.SUCCESSFUL);f.persistence.eventStatuses().append(one);f.persistence.eventStatuses().append(two);
        List<EventStatusAttempt> attempts=f.persistence.eventStatuses().findAttempts(first.metadata().eventId());check(attempts.size()==2&&attempts.get(0).status()==EventStatusValue.FAILED&&attempts.get(1).status()==EventStatusValue.SUCCESSFUL,"status attempts must remain append-only");
        expect(RuntimeException.class,()->f.persistence.events().append(first));check(f.persistence.events().findByWorkflowInstance(id).size()==2,"duplicate append must not mutate history");
    }

    private static void concurrentStatusAttemptsAreUniqueAndAppendOnly() throws Exception {
        Fixture f=fixture("concurrent-status");WorkflowEvent event=event(UUID.randomUUID(),WorkflowInstanceId.random(),"order.created","corr",null,Map.of(),Instant.now());
        EventStatusAttempt first=attempt(event,1,EventStatusValue.FAILED),second=attempt(event,2,EventStatusValue.SUCCESSFUL);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->f.persistence.transactions().execute(()->f.persistence.eventStatuses().append(first)));
            var two=pool.submit(()->f.second.transactions().execute(()->f.second.eventStatuses().append(second)));
            one.get();two.get();
        } finally { pool.shutdownNow(); }
        List<EventStatusAttempt> attempts=f.persistence.eventStatuses().findAttempts(event.metadata().eventId());
        check(attempts.size()==2&&attempts.get(0).attemptNumber()==1&&attempts.get(1).attemptNumber()==2,"concurrent status attempts must commit as two ordered immutable rows");
        expect(RuntimeException.class,()->f.persistence.eventStatuses().append(attempt(event,2,EventStatusValue.FAILED)));
        check(f.persistence.eventStatuses().findAttempts(event.metadata().eventId()).size()==2,"logical attempt uniqueness must reject collision without mutating history");
    }

    private static void timerStateMachineAndClaims() throws Exception {
        Fixture f=fixture("timers");Instant now=Instant.parse("2026-01-01T00:00:00Z");WorkflowTimerRepository timers=f.persistence.timers();
        WorkflowTimer first=timer(now.minusSeconds(2));WorkflowTimer second=timer(now.minusSeconds(1));timers.save(second);timers.save(first);
        List<WorkflowTimer> due=timers.dueTimers(now);check(due.get(0).timerId().equals(first.timerId()),"timers must use deterministic due ordering");
        List<WorkflowTimer> claimed=f.persistence.jdbcTransactions().inImmediateTransaction(()->timers.claimDue(now,"worker-a",now.plusSeconds(30),1));check(claimed.size()==1&&claimed.get(0).timerId().equals(first.timerId()),"first timer must be exclusively claimed");
        List<WorkflowTimer> competing=f.second.jdbcTransactions().inImmediateTransaction(()->f.second.timers().claimDue(now,"worker-b",now.plusSeconds(30),1));check(competing.size()==1&&competing.get(0).timerId().equals(second.timerId()),"competing worker must claim a different timer");
        timers.markFailed(first.timerId(),"worker-a","temporary",now.plusSeconds(10));check(timers.dueTimers(now).isEmpty(),"retry must honor next-attempt time");
        WorkflowTimer retry=f.persistence.jdbcTransactions().inImmediateTransaction(()->timers.claimDue(now.plusSeconds(11),"worker-a",now.plusSeconds(40),1)).get(0);timers.markFired(retry.timerId(),"worker-a",now.plusSeconds(12));
        timers.cancel(second.timerId(),now.plusSeconds(1));check(timers.dueTimers(now.plusSeconds(100)).isEmpty(),"completed and canceled timers must not be due");
        WorkflowTimer expired=timer(now.minusSeconds(1));timers.save(expired);f.persistence.jdbcTransactions().inImmediateTransaction(()->timers.claimDue(now,"dead-worker",now.plusSeconds(1),1));check(timers.releaseExpiredClaims(now.plusSeconds(2))==1,"expired timer lease must be released");check(f.second.jdbcTransactions().inImmediateTransaction(()->f.second.timers().claimDue(now.plusSeconds(2),"worker-c",now.plusSeconds(20),1)).size()==1,"released timer must be reclaimable");
    }

    private static void inboxDeduplicationAndStateMachine() throws Exception {
        Fixture f=fixture("inbox");Instant now=Instant.parse("2026-01-01T00:00:00Z");InboxMessage one=inbox("external-1",now);InboxMessage two=inbox("external-1",now);
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.inbox().insertIfAbsent(one)));var b=pool.submit(()->f.second.jdbcTransactions().inImmediateTransaction(()->f.second.inbox().insertIfAbsent(two)));InboxInsertResult ar=a.get(),br=b.get();check(ar.inserted()!=br.inserted()&&ar.message().messageId().equals(br.message().messageId()),"concurrent duplicate inbox delivery must resolve to one identity");}finally{pool.shutdownNow();}
        List<InboxMessage> claimed=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.inbox().claimEligible(now,"inbox-a",now.plusSeconds(10),1));check(claimed.size()==1,"inbox message must be claimed");check(f.second.jdbcTransactions().inImmediateTransaction(()->f.second.inbox().claimEligible(now,"inbox-b",now.plusSeconds(10),1)).isEmpty(),"inbox claim must be exclusive");
        UUID id=claimed.get(0).messageId();f.persistence.inbox().appendAttempt(new InboxAttempt(null,id,1,InboxMessageStatus.RETRY_SCHEDULED,"temporary","retry",now));f.persistence.inbox().scheduleRetry(id,"inbox-a",now.plusSeconds(2),"retry");
        InboxMessage retried=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.inbox().claimEligible(now.plusSeconds(3),"inbox-b",now.plusSeconds(20),1)).get(0);f.persistence.inbox().markProcessed(retried.messageId(),"inbox-b",now.plusSeconds(4));check(f.persistence.inbox().findById(id).orElseThrow().status()==InboxMessageStatus.PROCESSED,"inbox must reach processed state");check(f.persistence.inbox().findAttempts(id).size()==1,"inbox attempts must append");
        InboxMessage dead=inbox("external-2",now);f.persistence.inbox().insertIfAbsent(dead);UUID deadId=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.inbox().claimEligible(now,"inbox-c",now.plusSeconds(10),1)).get(0).messageId();f.persistence.inbox().markDeadLetter(deadId,"inbox-c","exhausted",now);check(f.persistence.inbox().findById(deadId).orElseThrow().status()==InboxMessageStatus.DEAD_LETTER,"inbox must dead-letter");
        InboxMessage abandoned=inbox("external-3",now);f.persistence.inbox().insertIfAbsent(abandoned);f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.inbox().claimEligible(now,"dead-inbox-worker",now.plusSeconds(1),1));check(f.persistence.inbox().releaseExpiredClaims(now.plusSeconds(2))==1,"expired inbox lease must release");check(f.second.jdbcTransactions().inImmediateTransaction(()->f.second.inbox().claimEligible(now.plusSeconds(2),"inbox-recovery",now.plusSeconds(20),1)).size()==1,"released inbox message must be reclaimable");
    }

    private static void outboxIdempotencyAndStateMachine() throws Exception {
        Fixture f=fixture("outbox");Instant now=Instant.parse("2026-01-01T00:00:00Z");OutboxMessage first=outbox("publish-1",now);OutboxMessage same=new OutboxMessage(UUID.randomUUID(),first.eventId(),first.destination(),first.idempotencyKey(),first.message(),first.correlationId(),first.causationId(),now,null,OutboxMessageStatus.PENDING,0,null,null,null,null);
        OutboxMessage stored=f.persistence.outbox().enqueue(first);check(f.persistence.outbox().enqueue(same).messageId().equals(stored.messageId()),"outbox enqueue must be idempotent");expect(PersistenceConstraintException.class,()->f.persistence.outbox().enqueue(outbox("publish-1",now)));
        OutboxMessage claimed=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.outbox().claimEligible(now,"publisher-a",now.plusSeconds(10),1)).get(0);check(f.second.jdbcTransactions().inImmediateTransaction(()->f.second.outbox().claimEligible(now,"publisher-b",now.plusSeconds(10),1)).isEmpty(),"outbox claim must be exclusive");
        f.persistence.outbox().appendAttempt(new OutboxAttempt(null,claimed.messageId(),1,OutboxMessageStatus.RETRY_SCHEDULED,"broker","down",now));f.persistence.outbox().scheduleRetry(claimed.messageId(),"publisher-a",now.plusSeconds(2),"down");OutboxMessage retry=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.outbox().claimEligible(now.plusSeconds(3),"publisher-b",now.plusSeconds(20),1)).get(0);f.persistence.outbox().markPublished(retry.messageId(),"publisher-b",now.plusSeconds(4));check(f.persistence.outbox().findById(retry.messageId()).orElseThrow().status()==OutboxMessageStatus.PUBLISHED,"outbox must reach published state");check(f.persistence.outbox().findAttempts(retry.messageId()).size()==1,"outbox attempts must append");
        OutboxMessage dead=outbox("publish-2",now);f.persistence.outbox().enqueue(dead);OutboxMessage deadClaim=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.outbox().claimEligible(now,"publisher-c",now.plusSeconds(1),1)).get(0);check(f.persistence.outbox().releaseExpiredClaims(now.plusSeconds(2))==1,"expired outbox lease must release");deadClaim=f.persistence.jdbcTransactions().inImmediateTransaction(()->f.persistence.outbox().claimEligible(now.plusSeconds(2),"publisher-d",now.plusSeconds(20),1)).get(0);f.persistence.outbox().markDeadLetter(deadClaim.messageId(),"publisher-d","exhausted",now.plusSeconds(3));check(f.persistence.outbox().findById(deadClaim.messageId()).orElseThrow().status()==OutboxMessageStatus.DEAD_LETTER,"outbox must dead-letter");
    }

    private static void commandResultIdempotency() throws Exception {Fixture f=fixture("commands");CommandResultRecord r=new CommandResultRecord("cmd-1","start","hash-a",null,Map.of("accepted",true),null);check(f.persistence.commandResults().save(r).equals(f.persistence.commandResults().save(r)),"same command result must be idempotent");expect(PersistenceConstraintException.class,()->f.persistence.commandResults().save(new CommandResultRecord("cmd-1","start","hash-b",null,Map.of(),null)));}

    private static void multiRepositoryRollbackIsAtomic() throws Exception {Fixture f=fixture("rollback");WorkflowSnapshot snapshot=snapshot();WorkflowEvent event=event(UUID.randomUUID(),snapshot.instanceId(),"order.created","corr",null,Map.of(),Instant.now());OutboxMessage outbox=outbox("atomic",Instant.now());CommandResultRecord command=new CommandResultRecord("atomic-command","start","hash",snapshot.instanceId(),Map.of("status","accepted"),null);expect(IllegalStateException.class,()->f.persistence.transactions().execute(()->{f.persistence.instances().insert(snapshot);f.persistence.events().append(event);f.persistence.outbox().enqueue(outbox);f.persistence.commandResults().save(command);throw new IllegalStateException("rollback");}));check(f.persistence.instances().findById(snapshot.instanceId()).isEmpty()&&f.persistence.events().find(event.metadata().eventId()).isEmpty()&&f.persistence.outbox().findById(outbox.messageId()).isEmpty()&&f.persistence.commandResults().find(command.idempotencyKey()).isEmpty(),"multi-repository failure must roll back every write including command results");}

    private static Fixture fixture(String name)throws Exception{Path db=Files.createTempFile("jworkflow-repository-"+name+"-",".sqlite");String url="jdbc:sqlite:"+db.toAbsolutePath();JdbcWorkflowPersistence first=JdbcWorkflowPersistence.create(url,null,null,new JDBC(),null,true,Map.of("sqlite.busy-timeout-ms","1000"));JdbcWorkflowPersistence second=JdbcWorkflowPersistence.create(url,null,null,new JDBC(),null,false,Map.of("sqlite.busy-timeout-ms","1000"));return new Fixture(url,first,second);}
    private static WorkflowSnapshot snapshot(){Instant n=Instant.now();return new WorkflowSnapshot(WorkflowInstanceId.random(),"orders","1","rev","o","corr","waiting",WorkflowStatus.WAITING,Map.of(),0,n,n);}
    private static WorkflowTimer timer(Instant due){return new WorkflowTimer(null,WorkflowInstanceId.random(),"step",due,"next",new EventName("timer.fired"),WorkflowTimerStatus.PENDING,0,due,null,null,due.minusSeconds(1),due.minusSeconds(1));}
    private static InboxMessage inbox(String external,Instant n){return new InboxMessage(null,external,"erp",new EventMessage(Map.of("value",1),"application/json","inbox","1",false,Map.of("h","v")),"corr","cause",n,null,InboxMessageStatus.RECEIVED,0,null,null,null,null);}
    private static OutboxMessage outbox(String key,Instant n){return new OutboxMessage(null,UUID.randomUUID(),"orders",key,new EventMessage(Map.of("value",1),"application/json","outbox","1",false,Map.of("h","v")),"corr","cause",n,null,OutboxMessageStatus.PENDING,0,null,null,null,null);}
    private static WorkflowEvent event(UUID eid,WorkflowInstanceId id,String name,String corr,String cause,Object payload,Instant n){return new WorkflowEvent(new EventMetadata(eid,new EventName(name),"test",corr,cause,"trace",id,"order","tenant","1",n,n,Map.of("header","value")),new EventMessage(payload,payload instanceof byte[]?"application/octet-stream":"application/json","event","schema-1",false,Map.of("attribute","value")));}
    private static EventStatusAttempt attempt(WorkflowEvent event,int number,EventStatusValue status){return new EventStatusAttempt(null,event.metadata().eventId(),null,number,"status-"+number,event.metadata().workflowInstanceId(),event.metadata().correlationId(),EventStatusScope.LISTENER,"listener","",status,number-1,null,status==EventStatusValue.FAILED,status==EventStatusValue.SUCCESSFUL,null,null,Instant.now());}
    private static String escaped(String value){return value.replace("'","''");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void expect(Class<? extends Throwable> type,Runnable work){try{work.run();throw new AssertionError("Expected "+type.getSimpleName());}catch(Throwable failure){if(!type.isInstance(failure))throw failure;}}
    private record Fixture(String url,JdbcWorkflowPersistence persistence,JdbcWorkflowPersistence second){}
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
