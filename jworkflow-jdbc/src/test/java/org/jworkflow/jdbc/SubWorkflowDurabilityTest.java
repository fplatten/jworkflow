package org.jworkflow.jdbc;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.*;
import org.jworkflow.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Durable sub-workflow calls persist the child instance and never leave timers for an unsaved instance. */
class SubWorkflowDurabilityTest {
    @TempDir Path directory;

    @Test void childIsSavedAndOnlyParentTimersArePersisted() throws Exception {
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("sub.db"));
        WorkflowDefinition child=WorkflowDefinitionBuilder.workflow("child").version("1").startAt("approval")
                .waitFor("approval",wait->wait.event("child.approved").correlateBy("businessKey").then("done")
                        .timeout(timeout->timeout.after(Duration.ofHours(1)).goTo("done")))
                .end("done").build();
        WorkflowDefinition parent=WorkflowDefinitionBuilder.workflow("parent").version("1").startAt("begin")
                .step("begin",step->step.action("noop").onSuccess("call"))
                .subWorkflow("call",call->call.workflow("child","1").onSuccess("child.succeeded","ok").onFailure("child.failed","failed"))
                .end("ok").end("failed").build();
        try(var engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).dataSource(source)
                .initialize(true).timerPolling(false).definition(child).definition(parent)
                .stepHandler("noop",context->StepResult.success()).build()) {
            WorkflowInstanceId id=engine.start("parent","order-1",Map.of());
            engine.signal(new SignalWorkflowCommand(id,new WorkflowSignal("begin.requested","corr",null,"order-1",Instant.now(),Map.of()),null));

            WorkflowSnapshot finished=engine.snapshot(id);
            assertEquals("failed",finished.state());
            WorkflowInstanceId childId=WorkflowInstanceId.fromString((String)finished.variables().get("__jworkflow.subWorkflow.call.instanceId"));
            assertEquals(WorkflowStatus.CANCELED,engine.snapshot(childId).status(),"the child instance must be saved");
            try(Connection connection=source.getConnection();PreparedStatement statement=connection.prepareStatement(
                    "select count(*) from workflow_timer where workflow_instance_id<>?")) {
                statement.setString(1,id.toString());
                try(ResultSet rows=statement.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1),"no timers may reference the child");}
            }
        }
    }
}
