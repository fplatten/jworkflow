package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventName;
import org.jworkflow.model.*;
import org.jworkflow.persistence.CommandResultRecord;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every timestamp the durable engine writes must come from its injected clock. The clock is set far in the future
 * (2099) so that any wall-clock read shows up as a value decades earlier than expected.
 */
public final class JdbcClockContractTest {
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    public static void main(String[] args) throws Exception {
        startWritesClockTimestamps();
        canceledTimerUsesInjectedClock();
        timerRetryAndFiringUseInjectedClock();
    }

    private static void startWritesClockTimestamps() throws Exception {
        Fixture f=fixture("start");WorkflowInstanceId id;
        try(JdbcWorkflowEngine engine=engine(f,definition("clock-start-flow"))){
            id=engine.start(new StartWorkflowCommand("clock-start-flow","1","order-s",Map.of(),metadata("clock-start",null,"clock-start-flow","order-s"))).workflowInstanceId();
        }
        JdbcWorkflowPersistence ports=ports(f);
        CommandResultRecord result=ports.commandResults().find("clock-start").orElseThrow(()->new AssertionError("start result must be stored"));
        check(FUTURE.equals(result.createdAt()),"command result created_at must use the injected clock: "+result.createdAt());
        WorkflowTimer timer=ports.timers().findByWorkflowInstance(id).get(0);
        check(FUTURE.equals(timer.createdAt()),"timer created_at must use the injected clock: "+timer.createdAt());
        check(FUTURE.equals(timer.updatedAt()),"timer updated_at must use the injected clock: "+timer.updatedAt());
        check(FUTURE.plusSeconds(10).equals(timer.dueAt()),"timer due_at must follow the injected clock: "+timer.dueAt());
        Instant definitionCreated=definitionCreatedAt(f,"clock-start-flow");
        check(FUTURE.equals(definitionCreated),"definition created_at must use the injected clock: "+definitionCreated);
    }

    private static void canceledTimerUsesInjectedClock() throws Exception {
        Fixture f=fixture("cancel");WorkflowInstanceId id;
        try(JdbcWorkflowEngine engine=engine(f,definition("clock-cancel-flow"))){
            id=engine.start(new StartWorkflowCommand("clock-cancel-flow","1","order-c",Map.of(),metadata("clock-cancel-start",null,"clock-cancel-flow","order-c"))).workflowInstanceId();
            f.clock.advance(Duration.ofSeconds(3));
            engine.signal(new SignalWorkflowCommand(id,new WorkflowSignal("approval.approved","corr-order-c",null,"order-c",f.clock.instant(),Map.of()),metadata("clock-cancel-signal",id,"clock-cancel-flow","order-c")));
            check(engine.snapshot(id).status()==WorkflowStatus.COMPLETED,"signal must complete the workflow");
        }
        WorkflowTimer timer=ports(f).timers().findByWorkflowInstance(id).get(0);
        check(timer.status()==WorkflowTimerStatus.CANCELED,"answered wait must cancel its timeout: "+timer.status());
        check(FUTURE.plusSeconds(3).equals(timer.updatedAt()),"canceled timer updated_at must use the injected clock: "+timer.updatedAt());
    }

    private static void timerRetryAndFiringUseInjectedClock() throws Exception {
        Fixture f=fixture("retry");WorkflowInstanceId id;
        try(JdbcWorkflowEngine engine=engine(f,definition("clock-retry-flow"))){
            id=engine.start(new StartWorkflowCommand("clock-retry-flow","1","order-r",Map.of(),metadata("clock-retry-start",null,"clock-retry-flow","order-r"))).workflowInstanceId();
            f.clock.advance(Duration.ofSeconds(11));
            AtomicInteger snapshots=new AtomicInteger();
            engine.writeProbe(stage->{if(stage.equals("snapshot")&&snapshots.incrementAndGet()==1)throw new IllegalStateException("injected");});
            try{engine.pollTimersOnce();}catch(RuntimeException expected){/* the injected failure may surface; the retry is what matters */}
            Instant failedAt=f.clock.instant();
            WorkflowTimer retrying=ports(f).timers().findByWorkflowInstance(id).get(0);
            check(retrying.status()==WorkflowTimerStatus.RETRY_SCHEDULED,"failed timer must be scheduled for retry: "+retrying.status());
            check(failedAt.equals(retrying.updatedAt()),"retry updated_at must use the injected clock: "+retrying.updatedAt());
            check(retrying.nextAttemptAt()!=null&&retrying.nextAttemptAt().isAfter(failedAt),"retry must be scheduled after the injected now: "+retrying.nextAttemptAt());
            f.clock.advance(Duration.between(failedAt,retrying.nextAttemptAt()).plusSeconds(1));
            check(engine.pollTimersOnce()==1,"retried timer must fire once due on the injected clock");
            Instant firedAt=f.clock.instant();
            WorkflowTimer fired=ports(f).timers().findByWorkflowInstance(id).get(0);
            check(fired.status()==WorkflowTimerStatus.FIRED,"retried timer must fire: "+fired.status());
            check(firedAt.equals(fired.updatedAt()),"fired timer updated_at must use the injected clock: "+fired.updatedAt());
        }
    }

    private static Instant definitionCreatedAt(Fixture f,String key)throws SQLException{
        try(Connection c=ContractBackend.open(f.url);PreparedStatement s=c.prepareStatement("select created_at from workflow_definition where workflow_key=?")){
            s.setString(1,key);
            try(ResultSet r=s.executeQuery()){
                check(r.next(),"definition row must exist");
                Object value=r.getObject(1);
                return value instanceof BigDecimal decimal ? PostgresqlInstantCodec.decode(decimal) : Instant.parse(value.toString());
            }
        }
    }
    private static WorkflowDefinition definition(String name){return WorkflowDefinition.of(name,"1","waiting",WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("approval.approved"),"businessKey","done"),new TimeoutDefinition(Duration.ofSeconds(10),"done",new EventName("approval.expired"))),WorkflowNode.end("done"));}
    private static JdbcWorkflowEngine engine(Fixture f,WorkflowDefinition definition)throws Exception{return (JdbcWorkflowEngine)ContractBackend.engine(f.url).initialize(true).clock(f.clock).timerPolling(false).definition(definition).build();}
    private static JdbcWorkflowPersistence ports(Fixture f){return ContractBackend.persistence(f.url,null,null,null,null,false,Map.of());}
    private static WorkflowCommandMetadata metadata(String key,WorkflowInstanceId id,String workflow,String business){return new WorkflowCommandMetadata(null,key,workflow,"1",id,business,"corr-"+business,null,null,null,"test",null,null,Map.of());}
    private static Fixture fixture(String name)throws Exception{return new Fixture(ContractBackend.url(Files.createTempFile("jworkflow-clock-"+name+"-",".sqlite").toAbsolutePath()),new MutableClock(FUTURE));}
    private record Fixture(String url,MutableClock clock){}
    private static final class MutableClock extends Clock{private Instant now;MutableClock(Instant now){this.now=now;}void advance(Duration d){now=now.plus(d);}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}

    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
