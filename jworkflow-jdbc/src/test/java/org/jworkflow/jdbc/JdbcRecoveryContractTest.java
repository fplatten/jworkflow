package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventName;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.*;
import org.jworkflow.persistence.PersistenceSerializationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JdbcRecoveryContractTest {
    public static void main(String[] args) throws Exception {
        externalWaitAndVariablesRecover();
        dueTimerExecutesExactlyOnceAfterRestart();
        expiredTimerClaimIsRecoveredButValidLeaseIsNotStolen();
        missingRevisionAndChecksumMismatchFailClearly();
        competingRecoveringEnginesDoNotExecuteOneTimerTwice();
    }

    private static void externalWaitAndVariablesRecover() throws Exception {
        Fixture f=fixture("wait");WorkflowDefinition definition=waitDefinition("wait-flow",null);WorkflowInstanceId id;
        try(JdbcWorkflowEngine first=engine(f,definition)){id=first.start(new StartWorkflowCommand("wait-flow","1","order-w",Map.of("nested",Map.of("values",Arrays.asList(1,true,null))),metadata("wait-start",null,"wait-flow","order-w"))).workflowInstanceId();}
        try(JdbcWorkflowEngine recovered=engine(f,null)){WorkflowSnapshot before=recovered.snapshot(id);check(before.correlationId().equals("corr-order-w"),"correlation must recover");check(before.variables().get("nested") instanceof Map,"nested variables must recover");
            recovered.signal(new SignalWorkflowCommand(id,new WorkflowSignal("approval.approved","corr-order-w",null,"order-w",f.clock.instant(),Map.of()),metadata("wait-signal",id,"wait-flow","order-w")));check(recovered.snapshot(id).status()==WorkflowStatus.COMPLETED,"recovered wait must accept its event");}
    }

    private static void dueTimerExecutesExactlyOnceAfterRestart() throws Exception {
        Fixture f=fixture("timer");WorkflowDefinition definition=waitDefinition("timer-flow",Duration.ofSeconds(10));WorkflowInstanceId id;
        try(JdbcWorkflowEngine first=engine(f,definition)){id=first.start(new StartWorkflowCommand("timer-flow","1","order-t",Map.of(),metadata("timer-start",null,"timer-flow","order-t"))).workflowInstanceId();}
        f.clock.advance(Duration.ofSeconds(11));List<WorkflowEvent> events=new CopyOnWriteArrayList<>();
        try(JdbcWorkflowEngine recovered=engine(f,null,events::add)){check(recovered.pollTimersOnce()==1,"one due timer must be claimed");check(recovered.pollTimersOnce()==0,"fired timer must not execute twice");check(recovered.snapshot(id).status()==WorkflowStatus.COMPLETED,"timer must complete recovered workflow");}
        check(events.stream().filter(e->"timer.fired".equals(e.eventName().value())).count()==1,"timer fired event must be observed exactly once");
        JdbcWorkflowPersistence ports=JdbcWorkflowPersistence.create(f.url,null,null,null,null,false,Map.of());WorkflowTimer timer=ports.timers().findByWorkflowInstance(id).get(0);
        check(ports.timers().findAttempts(timer.timerId()).size()==1&&ports.timers().findAttempts(timer.timerId()).get(0).status()==WorkflowTimerStatus.FIRED,"successful timer attempt history must be append-only");
    }

    private static void expiredTimerClaimIsRecoveredButValidLeaseIsNotStolen() throws Exception {
        Fixture f=fixture("lease");WorkflowDefinition definition=waitDefinition("lease-flow",Duration.ofSeconds(5));WorkflowInstanceId id;
        try(JdbcWorkflowEngine first=engine(f,definition)){id=first.start(new StartWorkflowCommand("lease-flow","1","order-l",Map.of(),metadata("lease-start",null,"lease-flow","order-l"))).workflowInstanceId();}
        f.clock.advance(Duration.ofSeconds(6));JdbcWorkflowPersistence ports=JdbcWorkflowPersistence.create(f.url,null,null,null,null,false,Map.of());
        List<WorkflowTimer> claimed=ports.jdbcTransactions().inImmediateTransaction(()->ports.timers().claimDue(f.clock.instant(),"crashed-worker",f.clock.instant().plusSeconds(5),1));check(claimed.size()==1,"crashed worker must claim timer");
        try(JdbcWorkflowEngine beforeExpiry=engine(f,null)){check(beforeExpiry.pollTimersOnce()==0,"valid lease must not be stolen");check(beforeExpiry.snapshot(id).status()!=WorkflowStatus.COMPLETED,"workflow must remain pending under valid lease");}
        f.clock.advance(Duration.ofSeconds(6));try(JdbcWorkflowEngine afterExpiry=engine(f,null)){check(afterExpiry.pollTimersOnce()==1,"expired claim must be reclaimed");check(afterExpiry.snapshot(id).status()==WorkflowStatus.COMPLETED,"reclaimed timer must execute");}
    }

    private static void missingRevisionAndChecksumMismatchFailClearly() throws Exception {
        Fixture missing=fixture("missing");WorkflowDefinition definition=waitDefinition("missing-flow",null);
        try(JdbcWorkflowEngine engine=engine(missing,definition)){engine.start(new StartWorkflowCommand("missing-flow","1","order-m",Map.of(),metadata("missing-start",null,"missing-flow","order-m")));}
        try(var c=DriverManager.getConnection(missing.url);var s=c.createStatement()){s.executeUpdate("update workflow_instance set workflow_revision='missing-revision'");}
        try(JdbcWorkflowEngine lazy=engine(missing,null)){WorkflowInstanceId id=lazy.context().getWorkflows().get(0).instanceId();try{lazy.signal(id,new WorkflowSignal("approval.approved","corr-order-m",null,"order-m",missing.clock.instant(),Map.of()));throw new AssertionError("missing exact revision must fail on access");}catch(WorkflowDefinitionNotFoundException expected){check(expected.getMessage().contains("missing-flow"),"missing revision diagnostic must name workflow");}}

        Fixture checksum=fixture("checksum");try(JdbcWorkflowEngine ignored=engine(checksum,waitDefinition("checksum-flow",null))){}
        try(var c=DriverManager.getConnection(checksum.url);var s=c.createStatement()){s.executeUpdate("update workflow_definition set checksum='corrupt'");}
        try{engine(checksum,null).close();throw new AssertionError("checksum mismatch must fail startup");}catch(PersistenceSerializationException expected){check(expected.getMessage().contains("checksum"),"checksum diagnostic required");}
    }

    private static void competingRecoveringEnginesDoNotExecuteOneTimerTwice() throws Exception {
        Fixture f=fixture("compete");WorkflowDefinition definition=waitDefinition("compete-flow",Duration.ofSeconds(2));
        try(JdbcWorkflowEngine first=engine(f,definition)){first.start(new StartWorkflowCommand("compete-flow","1","order-c",Map.of(),metadata("compete-start",null,"compete-flow","order-c")));}
        f.clock.advance(Duration.ofSeconds(3));AtomicInteger observations=new AtomicInteger();
        org.jworkflow.events.EventPublisher counter=e->{if("timer.fired".equals(e.eventName().value()))observations.incrementAndGet();};
        try(JdbcWorkflowEngine one=engine(f,null,counter);JdbcWorkflowEngine two=engine(f,null,counter)){
            ExecutorService pool=Executors.newFixedThreadPool(2);Future<Integer>a=pool.submit(one::pollTimersOnce),b=pool.submit(two::pollTimersOnce);int claims=a.get()+b.get();pool.shutdownNow();check(claims==1,"competing engines must produce one timer owner");check(observations.get()==1,"claimed timer must execute once");}
    }

    private static WorkflowDefinition waitDefinition(String name,Duration timeout){return WorkflowDefinition.of(name,"1","waiting",WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("approval.approved"),"businessKey","done"),timeout==null?null:new TimeoutDefinition(timeout,"done",new EventName("approval.expired"))),WorkflowNode.end("done"));}
    private static JdbcWorkflowEngine engine(Fixture f,WorkflowDefinition definition)throws Exception{return engine(f,definition,e->{});}private static JdbcWorkflowEngine engine(Fixture f,WorkflowDefinition definition,org.jworkflow.events.EventPublisher publisher)throws Exception{WorkflowEngineBuilder b=WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(f.url).initialize(true).clock(f.clock).timerPolling(false).eventPublisher(publisher);if(definition!=null)b.definition(definition);return (JdbcWorkflowEngine)b.build();}
    private static WorkflowCommandMetadata metadata(String key,WorkflowInstanceId id,String workflow,String business){return new WorkflowCommandMetadata(null,key,workflow,"1",id,business,"corr-"+business,null,null,null,"test",null,null,Map.of());}
    private static Fixture fixture(String name)throws Exception{Path file=Files.createTempFile("jworkflow-recovery-"+name+"-",".sqlite");return new Fixture("jdbc:sqlite:"+file.toAbsolutePath(),new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private record Fixture(String url,MutableClock clock){}
    private static final class MutableClock extends Clock{private Instant instant;private MutableClock(Instant instant){this.instant=instant;}void advance(Duration duration){instant=instant.plus(duration);}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return instant;}}
}
