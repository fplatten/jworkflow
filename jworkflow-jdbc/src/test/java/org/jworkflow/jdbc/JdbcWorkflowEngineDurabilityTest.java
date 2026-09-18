package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventName;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class JdbcWorkflowEngineDurabilityTest {
    public static void main(String[] args) throws Exception {
        restartContinuesFromRepositoryAndKeepsIdempotency();
        rollbackDoesNotPublishOrLeavePartialState();
        failedAdvanceRollsBackSnapshotAndEventTogether();
        forkJoinStateIsIsolatedAcrossInstancesAndRestart();
        productionJdbcPathHasNoInMemoryDelegate();
    }

    private static void restartContinuesFromRepositoryAndKeepsIdempotency() throws Exception {
        Path db=Files.createTempFile("jworkflow-runtime-",".sqlite");String url="jdbc:sqlite:"+db.toAbsolutePath();
        WorkflowDefinition definition=WorkflowDefinition.of("durable","1","created",
                WorkflowNode.step("created","order.create",List.of(WorkflowTransition.goTo("completed"))),WorkflowNode.end("completed"));
        WorkflowCommandMetadata startMetadata=metadata("start-1",null);
        JdbcWorkflowEngine first=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url).initialize(true).timerPolling(false).definition(definition).build();
        StartWorkflowCommand start=new StartWorkflowCommand("durable","1","order-1",Map.of("nested",Map.of("items",java.util.Arrays.asList(1,true,null))),startMetadata);
        StartWorkflowResult initial=first.start(start);first.close();

        JdbcWorkflowEngine second=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url).initialize(true).timerPolling(false).build();
        StartWorkflowResult repeated=second.start(start);
        check(repeated.idempotentRepeat()&&repeated.workflowInstanceId().equals(initial.workflowInstanceId()),"idempotency must survive engine reconstruction");
        WorkflowSignal signal=new WorkflowSignal("order.created","corr-1",null,"order-1",Instant.now(),Map.of());
        second.signal(new SignalWorkflowCommand(initial.workflowInstanceId(),signal,metadata("signal-1",initial.workflowInstanceId())));second.close();
        JdbcWorkflowEngine third=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url).initialize(true).timerPolling(false).build();
        check(third.snapshot(initial.workflowInstanceId()).status()==WorkflowStatus.COMPLETED,"a separate engine must continue persisted state");
        check(((Map<?,?>)third.snapshot(initial.workflowInstanceId()).variables().get("nested")).containsKey("items"),"nested variables must survive restart");
    }

    private static void rollbackDoesNotPublishOrLeavePartialState() throws Exception {
        Path db=Files.createTempFile("jworkflow-rollback-",".sqlite");AtomicInteger observed=new AtomicInteger();
        WorkflowDefinition definition=WorkflowDefinition.of("rollback","1","done",WorkflowNode.end("done"));
        JdbcWorkflowEngine engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl("jdbc:sqlite:"+db.toAbsolutePath()).initialize(true).timerPolling(false)
                .definition(definition).eventPublisher(event->observed.incrementAndGet()).build();
        engine.writeProbe(stage->{if("event".equals(stage))throw new IllegalStateException("injected after event write");});
        try{engine.start(new StartWorkflowCommand("rollback","1","order-r",Map.of(),metadata("rollback-start",null)));throw new AssertionError("expected injected failure");}
        catch(IllegalStateException expected){check(expected.getMessage().contains("injected"),"unexpected failure");}
        check(observed.get()==0,"post-commit observer must not run after rollback");
        check(engine.context().getWorkflows().isEmpty(),"rolled-back snapshot must not remain");
    }

    private static void failedAdvanceRollsBackSnapshotAndEventTogether() throws Exception {
        Path db=Files.createTempFile("jworkflow-advance-rollback-",".sqlite");String url="jdbc:sqlite:"+db.toAbsolutePath();
        WorkflowDefinition definition=WorkflowDefinition.of("advance-rollback","1","waiting",
                WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("order.approved"),"businessKey","done"),null),WorkflowNode.end("done"));
        try(JdbcWorkflowEngine engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url).initialize(true).timerPolling(false).definition(definition).build()){
            WorkflowInstanceId id=engine.start(new StartWorkflowCommand("advance-rollback","1","order-a",Map.of(),metadataFor("advance-start","advance-rollback","order-a",null))).workflowInstanceId();
            long beforeVersion=engine.snapshot(id).lockVersion();int beforeEvents=count(url,"workflow_event");
            engine.writeProbe(stage->{if("event".equals(stage))throw new IllegalStateException("advance failure after event append");});
            try{engine.signal(new SignalWorkflowCommand(id,new WorkflowSignal("order.approved","corr-order-a",null,"order-a",Instant.now(),Map.of()),metadataFor("advance-signal","advance-rollback","order-a",id)));throw new AssertionError("expected advance rollback");}
            catch(IllegalStateException expected){check(expected.getMessage().contains("advance failure"),"unexpected advance failure");}
            engine.writeProbe(null);WorkflowSnapshot unchanged=engine.snapshot(id);
            check("waiting".equals(unchanged.state())&&unchanged.lockVersion()==beforeVersion,"failed advance must roll back its guarded snapshot update");
            check(count(url,"workflow_event")==beforeEvents,"failed advance must roll back its appended event");
        }
    }

    private static void forkJoinStateIsIsolatedAcrossInstancesAndRestart() throws Exception {
        Path db=Files.createTempFile("jworkflow-fork-",".sqlite");String url="jdbc:sqlite:"+db.toAbsolutePath();java.util.ArrayList<WorkflowEvent> observed=new java.util.ArrayList<>();
        WorkflowDefinition definition=WorkflowDefinition.of("fork-durable","1","fork",
                WorkflowNode.fork("fork",new ForkDefinition(Map.of("left","left","right","right"),"joined")),
                WorkflowNode.step("left","left.run",List.of(WorkflowTransition.goTo("joined"))),
                WorkflowNode.step("right","right.run",List.of(WorkflowTransition.goTo("joined"))),
                WorkflowNode.join("joined",new JoinDefinition(List.of("left","right"),"done",new org.jworkflow.events.EventName("work.joined"))),WorkflowNode.end("done"));
        JdbcWorkflowEngine first=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url).initialize(true).timerPolling(false).definition(definition).eventPublisher(observed::add).build();
        WorkflowInstanceId one=first.start(new StartWorkflowCommand("fork-durable","1","one",Map.of(),metadataFor("fork-one","fork-durable","one",null))).workflowInstanceId();
        WorkflowInstanceId two=first.start(new StartWorkflowCommand("fork-durable","1","two",Map.of(),metadataFor("fork-two","fork-durable","two",null))).workflowInstanceId();
        String executionOne=execution(observed,one),executionTwo=execution(observed,two);check(!executionOne.equals(executionTwo),"fork executions must be isolated");first.close();
        JdbcWorkflowEngine restarted=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url).initialize(true).timerPolling(false).build();
        completeBranch(restarted,one,"one","left",executionOne,"one-left");completeBranch(restarted,two,"two","right",executionTwo,"two-right");
        check(restarted.snapshot(one).status()!=WorkflowStatus.COMPLETED&&restarted.snapshot(two).status()!=WorkflowStatus.COMPLETED,"one branch must not complete either join");
        completeBranch(restarted,one,"one","right",executionOne,"one-right");check(restarted.snapshot(one).status()==WorkflowStatus.COMPLETED,"first join must complete independently");
        check(restarted.snapshot(two).status()!=WorkflowStatus.COMPLETED,"first completion must not leak into second join");
        completeBranch(restarted,two,"two","left",executionTwo,"two-left");check(restarted.snapshot(two).status()==WorkflowStatus.COMPLETED,"second join must complete from its own branches");
    }
    private static void completeBranch(JdbcWorkflowEngine engine,WorkflowInstanceId id,String business,String branch,String execution,String key){
        engine.signal(new SignalWorkflowCommand(id,new WorkflowSignal("branch.completed","corr-"+business,null,business,Instant.now(),Map.of("branch",branch,"forkExecutionId",execution)),metadataFor(key,"fork-durable",business,id)));
    }
    private static String execution(List<WorkflowEvent> events,WorkflowInstanceId id){return events.stream().filter(e->"branch.started".equals(e.eventName().value())&&id.equals(e.metadata().workflowInstanceId())).findFirst().orElseThrow().metadata().headers().get("forkExecutionId");}
    private static int count(String url,String table)throws Exception{try(var connection=java.sql.DriverManager.getConnection(url);var statement=connection.createStatement();var rows=statement.executeQuery("select count(*) from "+table)){return rows.next()?rows.getInt(1):0;}}

    private static void productionJdbcPathHasNoInMemoryDelegate(){
        for(var field:JdbcWorkflowEngine.class.getDeclaredFields())check(!field.getType().equals(InMemoryWorkflowEngine.class),"JDBC engine must not retain an in-memory engine");
    }
    private static WorkflowCommandMetadata metadata(String key,WorkflowInstanceId id){return new WorkflowCommandMetadata(null,key,"durable","1",id,"order-1","corr-1",null,null,null,"test",null,null,Map.of());}
    private static WorkflowCommandMetadata metadataFor(String key,String workflow,String business,WorkflowInstanceId id){return new WorkflowCommandMetadata(null,key,workflow,"1",id,business,"corr-"+business,null,null,null,"test",null,null,Map.of());}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
