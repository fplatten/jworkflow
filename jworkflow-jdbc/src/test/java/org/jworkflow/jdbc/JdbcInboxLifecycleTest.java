package org.jworkflow.jdbc;

import org.jworkflow.application.Command;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.routing.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class JdbcInboxLifecycleTest {
    public static void main(String[] args)throws Exception{
        duplicateAndConcurrentAcceptanceDispatchOnce();
        sourceIsPartOfDeduplicationIdentity();
        metadataFailureRetryAndManualReprocessing();
        workflowWriteFailureRollsBackBeforeRetryFinalization();
        exhaustedAttemptsDeadLetterWithoutDeletingHistory();
        competingWorkersAndExpiredClaimRecovery();
        infrastructureAdapterTranslatesToCoreCommands();
        routedInboxPreservesPayloadAndDeduplicates();
    }
    private static void duplicateAndConcurrentAcceptanceDispatchOnce()throws Exception{
        Fixture f=fixture("duplicate",5);try(JdbcWorkflowEngine engine=engine(f,definition())){try(JdbcInboxApplication app=engine.inbox(translator())){
            InboxMessage message=message("external-1","erp","order-1");InboxInsertResult first=app.acceptAndProcess(message),duplicate=app.acceptAndProcess(message);
            check(first.inserted()&&!duplicate.inserted()&&first.message().messageId().equals(duplicate.message().messageId()),"duplicate must return stable identity");check(engine.context().getWorkflows().size()==1,"duplicate must dispatch one workflow command");check(app.find(first.message().messageId()).orElseThrow().status()==InboxMessageStatus.PROCESSED,"inbox and workflow must commit together");check(app.attempts(first.message().messageId()).size()==1,"success history required");
            InboxMessage concurrent=message("external-2","erp","order-2");ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<InboxInsertResult>a=pool.submit(()->app.accept(concurrent)),b=pool.submit(()->app.accept(concurrent));InboxInsertResult ar=a.get(15,java.util.concurrent.TimeUnit.SECONDS),br=b.get(15,java.util.concurrent.TimeUnit.SECONDS);check(ar.inserted()!=br.inserted(),"concurrent duplicate must insert once");}finally{pool.shutdownNow();}app.pollOnce();check(engine.context().getWorkflows().size()==2,"concurrent duplicate must have one workflow effect");
        }}}
    private static void sourceIsPartOfDeduplicationIdentity()throws Exception{
        Fixture f=fixture("sources",5);try(JdbcWorkflowEngine engine=engine(f,definition());JdbcInboxApplication app=engine.inbox(translator())){InboxInsertResult one=app.accept(message("shared","erp","source-a")),two=app.accept(message("shared","crm","source-b"));check(one.inserted()&&two.inserted()&&!one.message().messageId().equals(two.message().messageId()),"same external id from different sources must be distinct");app.pollOnce();check(engine.context().getWorkflows().size()==2,"both source identities must dispatch");}
    }
    private static void metadataFailureRetryAndManualReprocessing()throws Exception{
        Fixture f=fixture("retry",3);InboxMessage original=message("retry-1","gateway","order-r");UUID id;
        try(JdbcWorkflowEngine engine=engine(f,definition());JdbcInboxApplication broken=engine.inbox(message->List.of(new ReprocessInboxMessageCommand(message.messageId(),"test","unsupported",f.clock.instant())))){InboxInsertResult accepted=broken.acceptAndProcess(original);id=accepted.message().messageId();InboxMessage stored=broken.find(id).orElseThrow();check(stored.status()==InboxMessageStatus.RETRY_SCHEDULED,"failure must schedule retry");check(broken.attempts(id).size()==1,"failed attempt must append history");check(engine.context().getWorkflows().isEmpty(),"failed dispatch must leave no workflow effect");check(stored.message().equals(original.message())&&Objects.equals(stored.correlationId(),original.correlationId())&&Objects.equals(stored.causationId(),original.causationId()),"payload and metadata must survive failure");}
        f.clock.advance(Duration.ofSeconds(2));try(JdbcWorkflowEngine recovered=engine(f,null);JdbcInboxApplication corrected=recovered.inbox(translator())){InboxMessage completed=corrected.reprocess(new ReprocessInboxMessageCommand(id,"operator","fault corrected",f.clock.instant()));check(completed.status()==InboxMessageStatus.PROCESSED,"manual reprocessing must succeed");check(corrected.attempts(id).size()==2,"manual retry must preserve original history");check(recovered.context().getWorkflows().size()==1,"corrected retry must dispatch once");}
    }
    private static void exhaustedAttemptsDeadLetterWithoutDeletingHistory()throws Exception{
        Fixture f=fixture("dead",2);InboxMessage original=message("dead-1","gateway","order-d");UUID id;
        try(JdbcWorkflowEngine engine=engine(f,definition());JdbcInboxApplication broken=engine.inbox(m->List.of(new ReprocessInboxMessageCommand(m.messageId(),"test","bad",f.clock.instant())))){id=broken.acceptAndProcess(original).message().messageId();f.clock.advance(Duration.ofSeconds(2));broken.pollOnce();InboxMessage dead=broken.find(id).orElseThrow();check(dead.status()==InboxMessageStatus.DEAD_LETTER,"exhausted message must be queryable as dead letter");check(broken.attempts(id).size()==2&&broken.attempts(id).get(1).status()==InboxMessageStatus.DEAD_LETTER,"all attempts must remain append-only");}
    }
    private static void workflowWriteFailureRollsBackBeforeRetryFinalization()throws Exception{
        Fixture f=fixture("rollback",3);try(JdbcWorkflowEngine engine=engine(f,definition());JdbcInboxApplication app=engine.inbox(translator())){engine.writeProbe(stage->{if("snapshot".equals(stage))throw new IllegalStateException("injected workflow write failure");});InboxMessage input=message("rollback-1","erp","order-rb");UUID id=app.acceptAndProcess(input).message().messageId();engine.writeProbe(null);check(engine.context().getWorkflows().isEmpty(),"failed workflow write must roll back completely");check(app.find(id).orElseThrow().status()==InboxMessageStatus.RETRY_SCHEDULED,"rolled-back processing must finalize as retry");check(app.attempts(id).size()==1,"rollback failure must append exactly one failure attempt");}
    }
    private static void competingWorkersAndExpiredClaimRecovery()throws Exception{
        Fixture f=fixture("workers",5);WorkflowInstanceId expected;
        try(JdbcWorkflowEngine seed=engine(f,definition());JdbcInboxApplication accept=seed.inbox(translator())){accept.accept(message("worker-1","erp","order-w"));}
        try(JdbcWorkflowEngine one=engine(f,null);JdbcWorkflowEngine two=engine(f,null);JdbcInboxApplication a=one.inbox(translator());JdbcInboxApplication b=two.inbox(translator())){ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<Integer>x=pool.submit(a::pollOnce),y=pool.submit(b::pollOnce);check(x.get(15,java.util.concurrent.TimeUnit.SECONDS)+y.get(15,java.util.concurrent.TimeUnit.SECONDS)==1,"competing workers must claim once");}finally{pool.shutdownNow();}int active=one.context().getWorkflows().size();check(active==1,"one claimed message must create one workflow, found "+active);}
        InboxMessage crash=message("crash-1","erp","order-c");JdbcWorkflowPersistence ports=ContractBackend.persistence(f.url,null,null,null,null,false,Map.of());InboxInsertResult accepted=ports.transactions().inTransaction(()->ports.inbox().insertIfAbsent(crash));ports.jdbcTransactions().inImmediateTransaction(()->ports.inbox().claimEligible(f.clock.instant(),"crashed",f.clock.instant().plusSeconds(5),1));
        try(JdbcWorkflowEngine before=engine(f,null);JdbcInboxApplication app=before.inbox(translator())){check(app.pollOnce()==0,"unexpired inbox lease must not be stolen");}f.clock.advance(Duration.ofSeconds(6));try(JdbcWorkflowEngine after=engine(f,null);JdbcInboxApplication app=after.inbox(translator())){check(app.pollOnce()==1,"expired inbox lease must recover");check(app.find(accepted.message().messageId()).orElseThrow().status()==InboxMessageStatus.PROCESSED,"recovered claim must process");}
    }
    private static void infrastructureAdapterTranslatesToCoreCommands()throws Exception{
        Fixture f=fixture("adapter",5);java.util.concurrent.atomic.AtomicReference<InboxMessage> translated=new java.util.concurrent.atomic.AtomicReference<>();InboxEventTranslator translator=message->{translated.set(message);return JdbcInboxLifecycleTest.translator().translate(message);};
        try(JdbcWorkflowEngine engine=engine(f,definition());JdbcInboxApplication app=engine.inbox(translator)){WorkflowEvent external=new WorkflowEvent(new EventMetadata(null,new EventName("order.received"),"broker-a","corr-adapter","cause-adapter",null,null,"adapter-order",null,"1",f.clock.instant(),f.clock.instant(),Map.of("transport","test")),new EventMessage(Map.of("businessKey","adapter-order"),"application/json","order-event","7",false,Map.of("header","value")));InboxInsertResult result=new JdbcInboxEventAdapter(app).onEvent(external);check(result.inserted()&&translated.get()!=null,"listener adapter must translate accepted event");check("7".equals(translated.get().message().schemaVersion())&&"cause-adapter".equals(translated.get().causationId()),"adapter must preserve message metadata");check(engine.context().getWorkflows().size()==1,"adapter must dispatch a core command");}
    }
    private static void routedInboxPreservesPayloadAndDeduplicates()throws Exception{
        Fixture f=fixture("routed",5);WorkflowDefinition waiting=WorkflowDefinition.of("routed-inbox","1","tax",WorkflowNode.waitFor("tax",new WaitDefinition(new EventName("tax.completed"),"employeeId","done"),null),WorkflowNode.end("done"));
        try(JdbcWorkflowEngine engine=engine(f,waiting)){WorkflowCommandMetadata metadata=new WorkflowCommandMetadata(null,"routed-start","routed-inbox","1",null,"employee-9","corr-employee-9",null,null,null,"test",null,null,Map.of());WorkflowInstanceId id=engine.start(new StartWorkflowCommand("routed-inbox","1","employee-9",Map.of(),metadata)).workflowInstanceId();
            InboxRoutingTranslator routing=message->{EventMetadata eventMetadata=new EventMetadata(UUID.fromString(message.externalEventId()),new EventName("tax.completed"),message.sourceSystem(),message.correlationId(),message.causationId(),"trace-tax",null,"employee-9",null,"1",message.receivedAt(),message.receivedAt(),message.message().attributes());WorkflowEvent event=new WorkflowEvent(eventMetadata,message.message());return List.of(new RouteWorkflowEventCommand(event,WorkflowEventRoute.exact(id,event.eventName())));};
            try(JdbcInboxApplication app=engine.inbox(routing.asCommandTranslator())){InboxMessage input=new InboxMessage(null,UUID.randomUUID().toString(),"tax-service",new EventMessage(Map.of("withholding","W4","allowances",2),"application/json","tax-form","3",false,Map.of("documentId","doc-9")),"corr-employee-9","cause-tax",f.clock.instant(),null,null,0,null,null,null,null);InboxInsertResult first=app.acceptAndProcess(input),duplicate=app.acceptAndProcess(input);check(first.inserted()&&!duplicate.inserted(),"routed duplicate inbox delivery was dispatched twice");WorkflowSnapshot completed=engine.snapshot(id);check(completed.status()==WorkflowStatus.COMPLETED&&"W4".equals(completed.variables().get("withholding")),"routed inbox payload did not reach workflow transition");check(app.attempts(first.message().messageId()).size()==1,"duplicate routed inbox delivery appended another attempt");InboxMessage rejected=new InboxMessage(null,UUID.randomUUID().toString(),"tax-service",input.message(),input.correlationId(),input.causationId(),f.clock.instant(),null,null,0,null,null,null,null);UUID rejectedId=app.acceptAndProcess(rejected).message().messageId();InboxAttempt failure=app.attempts(rejectedId).get(0);check(failure.errorCode().equals("workflow_routing_terminal_workflow"),"routing failure did not append an actionable error code: "+failure.errorCode());}
        }
    }
    private static InboxEventTranslator translator(){return m->{Map<?,?>p=(Map<?,?>)m.message().payload();String business=(String)p.get("businessKey");WorkflowCommandMetadata metadata=new WorkflowCommandMetadata(null,m.deduplicationKey(),"inbox-flow","1",null,business,m.correlationId(),m.causationId(),null,null,m.sourceSystem(),null,m.receivedAt(),m.message().attributes());return List.of(new StartWorkflowCommand("inbox-flow","1",business,Map.of("payload",p),metadata));};}
    private static WorkflowDefinition definition(){return WorkflowDefinition.of("inbox-flow","1","pending",WorkflowNode.step("pending","order.process",List.of(WorkflowTransition.goTo("done"))),WorkflowNode.end("done"));}
    private static InboxMessage message(String external,String source,String business){EventMessage payload=new EventMessage(Map.of("businessKey",business,"amount",42),"application/json","order-event","2",false,Map.of("tenant","north"));return new InboxMessage(null,external,source,payload,"corr-"+business,"cause-"+business,null,null,null,0,null,null,null,null);}
    private static JdbcWorkflowEngine engine(Fixture f,WorkflowDefinition definition)throws Exception{WorkflowEngineBuilder b=ContractBackend.engine(f.url).initialize(true).clock(f.clock).timerPolling(false).setting("inbox.max-attempts",Integer.toString(f.maxAttempts)).setting("inbox.retry-initial-ms","1000").setting("inbox.retry-maximum-ms","5000");if(definition!=null)b.definition(definition);return (JdbcWorkflowEngine)b.build();}
    private static Fixture fixture(String name,int max)throws Exception{return new Fixture(ContractBackend.url(Files.createTempFile("jworkflow-inbox-"+name+"-",".sqlite").toAbsolutePath()),new MutableClock(Instant.parse("2026-01-01T00:00:00Z")),max);}private record Fixture(String url,MutableClock clock,int maxAttempts){}
    private static final class MutableClock extends Clock{private Instant now;MutableClock(Instant now){this.now=now;}void advance(Duration d){now=now.plus(d);}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
