package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.routing.*;
import org.jworkflow.persistence.WorkflowOptimisticLockException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class JdbcEventRoutingContractTest {
    private JdbcEventRoutingContractTest() { }

    public static void main(String[] args) throws Exception {
        exactScopedBusinessAndRestartRouting();
        ambiguityRejectionAndExplicitFanOut();
        rejectionDoesNotMutateAndTerminalIsNotSelected();
        concurrentDeliveryCannotOverwrite();
        payloadAndSchemaReachStepHandler();
    }

    private static void exactScopedBusinessAndRestartRouting() throws Exception {
        Fixture fixture=fixture("identities");WorkflowInstanceId exact,scopedA,scopedB,business;
        try(JdbcWorkflowEngine engine=engine(fixture,wait("exact"),wait("flow-a"),wait("flow-b"),wait("business"))){
            exact=start(engine,"exact","employee-exact","corr-exact");
            scopedA=start(engine,"flow-a","employee-a","shared-correlation");
            scopedB=start(engine,"flow-b","employee-b","shared-correlation");
            business=start(engine,"business","employee-business","corr-business");
            WorkflowEvent exactEvent=event("tax.completed",exact,"corr-exact","employee-exact",Map.of("taxStatus","complete"),Map.of());
            check(engine.route(exactEvent,WorkflowEventRoute.exact(exact,exactEvent.eventName())).outcome()==WorkflowRoutingOutcome.ROUTED,"exact route failed");
            check("complete".equals(engine.snapshot(exact).variables().get("taxStatus")),"event payload did not reach workflow variables");

            WorkflowEvent scoped=event("tax.completed",null,"shared-correlation",null,Map.of("source","payroll"),Map.of("workflowKey","flow-a"));
            check(engine.route(scoped,WorkflowEventRoute.correlated("flow-a","shared-correlation",scoped.eventName())).routedInstances().equals(List.of(scopedA)),"scoped correlation selected wrong workflow");
            check(engine.snapshot(scopedB).status()!=WorkflowStatus.COMPLETED,"same correlation in another workflow key was routed");

            WorkflowEvent byBusiness=event("tax.completed",null,null,"employee-business",Map.of(),Map.of("workflowKey","business"));
            check(engine.route(byBusiness,WorkflowEventRoute.businessKey("business","employee-business",byBusiness.eventName())).routedInstances().equals(List.of(business)),"business-key route failed");
        }
        try(JdbcWorkflowEngine restarted=engine(fixture)){
            WorkflowEvent afterRestart=event("tax.completed",scopedB,"shared-correlation","employee-b",Map.of("afterRestart",true),Map.of());
            check(restarted.route(afterRestart,WorkflowEventRoute.exact(scopedB,afterRestart.eventName())).outcome()==WorkflowRoutingOutcome.ROUTED,"routing failed after restart");
            check(Boolean.TRUE.equals(restarted.snapshot(scopedB).variables().get("afterRestart")),"restart route lost payload");
        }
    }

    private static void ambiguityRejectionAndExplicitFanOut() throws Exception {
        Fixture fixture=fixture("fanout");
        try(JdbcWorkflowEngine engine=engine(fixture,wait("fan"))){
            WorkflowInstanceId first=start(engine,"fan","employee-1","group-1");WorkflowInstanceId second=start(engine,"fan","employee-2","group-1");
            WorkflowEvent event=event("tax.completed",null,"group-1",null,Map.of(),Map.of("workflowKey","fan"));
            try{engine.route(event,WorkflowEventRoute.correlated("fan","group-1",event.eventName()));throw new AssertionError("ambiguous route was accepted");}catch(AmbiguousWorkflowRouteException expected){ }
            check(engine.snapshot(first).lockVersion()==0&&engine.snapshot(second).lockVersion()==0,"ambiguous route mutated workflows");
            WorkflowRoutingResult fanout=engine.route(event,WorkflowEventRoute.correlated("fan","group-1",event.eventName()).fanOut());
            check(fanout.outcome()==WorkflowRoutingOutcome.ROUTED&&new HashSet<>(fanout.routedInstances()).equals(Set.of(first,second)),"explicit fan-out did not route every target");
        }
    }

    private static void rejectionDoesNotMutateAndTerminalIsNotSelected() throws Exception {
        Fixture fixture=fixture("reject");
        try(JdbcWorkflowEngine engine=engine(fixture,wait("reject"))){
            WorkflowInstanceId id=start(engine,"reject","employee-r","corr-r");WorkflowSnapshot before=engine.snapshot(id);
            WorkflowEvent wrong=event("employee.updated",id,"corr-r","employee-r",Map.of(),Map.of());
            WorkflowRoutingResult ignored=engine.route(wrong,WorkflowEventRoute.exact(id,wrong.eventName()));
            WorkflowSnapshot after=engine.snapshot(id);
            check(ignored.outcome()==WorkflowRoutingOutcome.EVENT_NOT_ACCEPTED,"wrong event was not rejected");
            check(after.lockVersion()==before.lockVersion()&&after.updatedAt().equals(before.updatedAt()),"rejected event mutated snapshot");
            WorkflowEvent accepted=event("tax.completed",id,"corr-r","employee-r",Map.of(),Map.of());engine.route(accepted,WorkflowEventRoute.exact(id,accepted.eventName()));
            WorkflowRoutingResult terminal=engine.route(event("tax.completed",id,"corr-r","employee-r",Map.of(),Map.of()),WorkflowEventRoute.exact(id,accepted.eventName()));
            check(terminal.outcome()==WorkflowRoutingOutcome.TERMINAL_WORKFLOW,"terminal workflow was selected");
        }
    }

    private static void concurrentDeliveryCannotOverwrite() throws Exception {
        Fixture fixture=fixture("concurrent");
        try(JdbcWorkflowEngine engine=engine(fixture,wait("concurrent"))){
            WorkflowInstanceId id=start(engine,"concurrent","employee-c","corr-c");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
            Callable<Object> work=()->{gate.await();WorkflowEvent event=event("tax.completed",id,"corr-c","employee-c",Map.of(),Map.of());try{return engine.route(event,WorkflowEventRoute.exact(id,event.eventName())).outcome();}catch(RuntimeException conflict){return conflict;}};
            Future<Object>a=pool.submit(work),b=pool.submit(work);gate.countDown();Object left,right;try{left=a.get();right=b.get();}finally{pool.shutdownNow();}
            long routed=java.util.stream.Stream.of(left,right).filter(value->value==WorkflowRoutingOutcome.ROUTED).count();check(routed==1,"concurrent events produced duplicate successful transitions");
            check(engine.snapshot(id).status()==WorkflowStatus.COMPLETED,"winning concurrent route did not complete workflow");
        }
    }

    private static void payloadAndSchemaReachStepHandler()throws Exception{
        Fixture fixture=fixture("metadata");AtomicReference<EventMessage> received=new AtomicReference<>();WorkflowNode step=WorkflowNode.step("receive","tax.receive",List.of(new WorkflowTransition("success","done",null,new EventName("tax.completed"))));WorkflowDefinition definition=WorkflowDefinition.of("metadata","1","receive",step,WorkflowNode.end("done"));
        WorkflowEngineBuilder builder=WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(fixture.url()).initialize().timerPolling(false).definition(definition).stepHandler("tax.receive",context->{received.set(context.signal().message());return StepResult.success();});
        try(JdbcWorkflowEngine engine=(JdbcWorkflowEngine)builder.build()){WorkflowInstanceId id=start(engine,"metadata","employee-m","corr-m");WorkflowEvent event=event("tax.completed",id,"corr-m","employee-m",Map.of("field","value"),Map.of("transport","inbox"));engine.route(event,WorkflowEventRoute.exact(id,event.eventName()));EventMessage message=received.get();check(message!=null&&"application/json".equals(message.contentType())&&"tax-form".equals(message.schemaName())&&"1".equals(message.schemaVersion())&&Map.of("field","value").equals(message.payload()),"payload or schema metadata did not reach step handler");}
    }

    private static WorkflowDefinition wait(String name){return WorkflowDefinition.of(name,"1","tax",WorkflowNode.waitFor("tax",new WaitDefinition(new EventName("tax.completed"),"employeeId","done"),null),WorkflowNode.end("done"));}
    private static WorkflowInstanceId start(JdbcWorkflowEngine engine,String workflow,String business,String correlation){WorkflowCommandMetadata metadata=new WorkflowCommandMetadata(null,"start:"+workflow+":"+business,workflow,"1",null,business,correlation,null,"trace-1",null,"test",null,null,Map.of());return engine.start(new StartWorkflowCommand(workflow,"1",business,Map.of("employeeId",business),metadata)).workflowInstanceId();}
    private static WorkflowEvent event(String name,WorkflowInstanceId id,String correlation,String business,Object payload,Map<String,String> headers){Instant now=Instant.now();return new WorkflowEvent(new EventMetadata(null,new EventName(name),"external-hr",correlation,"cause-1","trace-1",id,business,null,"tax-v1",now,now,headers),new EventMessage(payload,"application/json","tax-form","1",false,Map.of("origin","hr")));}
    private static JdbcWorkflowEngine engine(Fixture fixture,WorkflowDefinition...definitions)throws Exception{WorkflowEngineBuilder builder=WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(fixture.url()).initialize().timerPolling(false).eventRoutingMaximumCandidates(10).lazyDefinitionValidation(true);for(WorkflowDefinition definition:definitions)builder.definition(definition);return(JdbcWorkflowEngine)builder.build();}
    private static Fixture fixture(String name)throws Exception{Path file=Files.createTempFile("jworkflow-routing-"+name+"-",".sqlite");return new Fixture("jdbc:sqlite:"+file.toAbsolutePath());}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private record Fixture(String url){ }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
