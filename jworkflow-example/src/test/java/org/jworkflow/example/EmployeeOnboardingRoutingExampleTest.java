package org.jworkflow.example;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.jdbc.JdbcWorkflowEngine;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.routing.WorkflowEventRoute;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** Long-running onboarding workflow resumed by an external tax-service event after engine restart. */
public final class EmployeeOnboardingRoutingExampleTest {
    private EmployeeOnboardingRoutingExampleTest() { }

    public static void main(String[] args) throws Exception {
        Path database=Files.createTempFile("jworkflow-onboarding-",".sqlite");WorkflowInstanceId id;
        WorkflowDefinitionBuilder onboarding=WorkflowDefinitionBuilder.workflow("employee-onboarding")
                .version("1.0.0").startAt("await-tax-form")
                .waitFor("await-tax-form",wait->wait.event("tax.completed").correlateBy("employeeId").then("onboarded"))
                .end("onboarded");
        try(JdbcWorkflowEngine first=engine(database,onboarding)){
            WorkflowCommandMetadata metadata=new WorkflowCommandMetadata(null,"employee-42:start","employee-onboarding","1.0.0",null,"employee-42","employee-42",null,"trace-onboarding",null,"hr",null,null,Map.of());
            id=first.start(new StartWorkflowCommand("employee-onboarding","1.0.0","employee-42",Map.of("employeeId","employee-42"),metadata)).workflowInstanceId();
        }
        try(JdbcWorkflowEngine restarted=engine(database,null)){
            Instant now=Instant.now();WorkflowEvent completed=new WorkflowEvent(new EventMetadata(null,new EventName("tax.completed"),"tax-service","employee-42","tax-submission-7","trace-onboarding",null,"employee-42",null,"1",now,now,Map.of("workflowKey","employee-onboarding")),new EventMessage(Map.of("form","W-4","documentId","tax-7"),"application/json","tax-form","3",false,Map.of()));
            restarted.route(completed,WorkflowEventRoute.correlated("employee-onboarding","employee-42",completed.eventName()));
            if(restarted.snapshot(id).status()!=WorkflowStatus.COMPLETED)throw new AssertionError("Onboarding workflow did not resume after restart");
        }finally{Files.deleteIfExists(database);}
    }

    private static JdbcWorkflowEngine engine(Path database,WorkflowDefinitionBuilder definition)throws Exception{WorkflowEngineBuilder builder=WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl("jdbc:sqlite:"+database).initialize().timerPolling(false);if(definition!=null)builder.definition(definition);return(JdbcWorkflowEngine)builder.build();}
}
