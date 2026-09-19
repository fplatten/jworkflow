package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventName;
import org.jworkflow.events.EventMetadata;
import org.jworkflow.events.EventMessage;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.*;
import org.jworkflow.routing.WorkflowEventRoute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;

public final class JdbcStartupScalabilityTest {
    private JdbcStartupScalabilityTest() { }

    public static void main(String[] args) throws Exception {
        boundedKeysetAuditAndLazyStartup();
        corruptInstanceIsIsolatedUntilAccess();
        builderBoundsAreEnforced();
    }

    private static void boundedKeysetAuditAndLazyStartup() throws Exception {
        String url=fixture("bounded");WorkflowDefinition definition=definition("scale");
        try(JdbcWorkflowEngine engine=engine(url,true,3,definition)){for(int i=0;i<8;i++)engine.start("scale","employee-"+i,Map.of());}
        try(JdbcWorkflowEngine audited=engine(url,false,3)){
            check(audited.startupValidationQueryCount()==3,"eight instances with batch three must use three keyset pages");
            check(audited.startupValidationPeakBatchSize()<=3,"startup query exceeded configured batch size");
            check(JdbcWorkflowEngine.RESIDENT_WORKFLOW_COUNT == 0,"startup retained workflow snapshots in memory");
        }
        try(JdbcWorkflowEngine lazy=engine(url,true,2)){
            check(lazy.startupValidationQueryCount()==0,"lazy startup scanned active workflows");
        }
    }

    private static void corruptInstanceIsIsolatedUntilAccess() throws Exception {
        String url=fixture("isolation");WorkflowDefinition definition=definition("isolated");WorkflowInstanceId good,bad;
        try(JdbcWorkflowEngine engine=engine(url,true,2,definition)){good=engine.start("isolated","good",Map.of());bad=engine.start("isolated","bad",Map.of());}
        try(var connection=ContractBackend.open(url);var statement=connection.prepareStatement("update workflow_instance set workflow_revision='missing' where id=?")){statement.setString(1,bad.toString());statement.executeUpdate();}
        try(JdbcWorkflowEngine lazy=engine(url,true,2)){
            WorkflowEvent event=event(good,"good");lazy.route(event,WorkflowEventRoute.exact(good,event.eventName()));
            check(lazy.snapshot(good).status()==WorkflowStatus.COMPLETED,"valid workflow could not progress beside corrupt workflow");
            try{WorkflowEvent badEvent=event(bad,"bad");lazy.route(badEvent,WorkflowEventRoute.exact(bad,badEvent.eventName()));throw new AssertionError("corrupt revision was not rejected on access");}catch(WorkflowDefinitionNotFoundException expected){ }
        }
    }

    private static void builderBoundsAreEnforced() {
        expectFailure(()->WorkflowEngine.builder().startupValidationBatchSize(0));
        expectFailure(()->WorkflowEngine.builder().startupValidationBatchSize(10_001));
        expectFailure(()->WorkflowEngine.builder().eventRoutingMaximumCandidates(0));
        expectFailure(()->WorkflowEngine.builder().eventRoutingMaximumCandidates(10_001));
    }

    private static WorkflowDefinition definition(String name){return WorkflowDefinition.of(name,"1","waiting",WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("tax.completed"),"employeeId","done"),null),WorkflowNode.end("done"));}
    private static WorkflowEvent event(WorkflowInstanceId id,String business){Instant now=Instant.now();return new WorkflowEvent(new EventMetadata(null,new EventName("tax.completed"),"test","corr",null,null,id,business,null,"1",now,now,Map.of()),EventMessage.json(Map.of("received",true)));}
    private static JdbcWorkflowEngine engine(String url,boolean lazy,int batch,WorkflowDefinition...definitions)throws Exception{WorkflowEngineBuilder builder=ContractBackend.engine(url).initialize().timerPolling(false).lazyDefinitionValidation(lazy).startupValidationBatchSize(batch);for(WorkflowDefinition definition:definitions)builder.definition(definition);return(JdbcWorkflowEngine)builder.build();}
    private static String fixture(String name)throws Exception{Path file=Files.createTempFile("jworkflow-startup-"+name+"-",".sqlite");return ContractBackend.url(file.toAbsolutePath());}
    private static void expectFailure(Runnable operation){try{operation.run();throw new AssertionError("Expected bounded configuration failure");}catch(IllegalArgumentException expected){ }}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
