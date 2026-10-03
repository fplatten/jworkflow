package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.PersistenceSerializationException;
import org.jworkflow.query.PersistenceWorkflowQueryService;
import org.jworkflow.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.TransactionNotificationContract.*;

/** JDBC regression tests for type-preserving JSON, queries, inbox acceptance and fan-out isolation. */
class MediumFixesJdbcTest {
    @TempDir Path directory;

    @Test void numbersKeepTheirTypeThroughPersistence() {
        JdbcJsonCodec codec=new JdbcJsonCodec();
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("amount",new BigDecimal("12345678901234567890.12"));
        value.put("count",5L);
        value.put("ints",List.of(1,2.5d,(short)3,(byte)4,1.5f,new BigInteger("7")));
        Map<String,Object> read=codec.readMap(codec.write(value));
        assertEquals(new BigDecimal("12345678901234567890.12"),read.get("amount"));
        assertEquals(5L,read.get("count"));
        assertEquals(List.of(1,2.5d,(short)3,(byte)4,1.5f,new BigInteger("7")),read.get("ints"));
        assertTrue(codec.write(Map.of("plain",1,"ratio",0.5d)).contains("\"version\":1"),"untyped values keep format version 1");
        assertThrows(PersistenceSerializationException.class,()->codec.write(Map.of("@jworkflow.number","long","value","1")));
    }

    @Test void pendingWaitsLooksPastNonWaitingInstances() throws Exception {
        var source=source("waits");
        WorkflowDefinition stepper=WorkflowDefinition.of("stepper","1","s",
                WorkflowNode.step("s","noop",List.of(WorkflowTransition.goTo("done"))),WorkflowNode.end("done"));
        try(var engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).dataSource(source).initialize(true)
                .timerPolling(false).definition(stepper).definition(definition()).build()) {
            engine.start("stepper","s-1",Map.of());engine.start("stepper","s-2",Map.of());
            WorkflowInstanceId waiting=engine.start(command("w-1")).workflowInstanceId();
            var queries=new PersistenceWorkflowQueryService(JdbcWorkflowPersistence.from(engine.connectionFactory()));
            assertEquals(List.of(waiting),queries.pendingWaits(1).stream().map(view->view.instanceId()).toList());
        }
    }

    @Test void correlationLookupIgnoresFinishedInstances() throws Exception {
        var source=source("correlation");
        try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.SQLITE,source,event->{})) {
            WorkflowInstanceId done=engine.start(command("c-1")).workflowInstanceId();
            engine.signal(new SignalWorkflowCommand(done,new WorkflowSignal("order.approved","corr",null,"c-1",Instant.now(),Map.of()),null));
            WorkflowInstanceId active=engine.start(command("c-2")).workflowInstanceId();
            var instances=JdbcWorkflowPersistence.from(engine.connectionFactory()).instances();
            assertEquals(active,instances.findByCorrelationId("corr").orElseThrow().instanceId());
        }
    }

    @Test void acceptedMessageIsProcessedWhateverStatusItWasSentWith() throws Exception {
        var source=source("accept");
        try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.SQLITE,source,event->{});
            JdbcInboxApplication app=engine.inbox(message->List.of(command("a-1")))) {
            InboxMessage sent=new InboxMessage(null,"external-a","erp",EventMessage.empty(),"corr",null,null,
                    Instant.now(),InboxMessageStatus.PROCESSED,3,null,"old",null,null);
            UUID id=app.accept(sent).message().messageId();
            assertEquals(1,app.pollOnce());
            assertEquals(InboxMessageStatus.PROCESSED,app.find(id).orElseThrow().status());
            assertEquals(1,count(source,"workflow_instance"));
        }
    }

    @Test void failingFanOutTargetDoesNotBlockTheOthers() throws Exception {
        var source=source("fanout");
        try(var engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).dataSource(source).initialize(true)
                .timerPolling(false).definition(definition()).setting("inbox.retry-initial-ms","10").build()) {
            WorkflowInstanceId first=engine.start(command("f-1")).workflowInstanceId();
            WorkflowInstanceId second=engine.start(command("f-2")).workflowInstanceId();
            WorkflowEvent approved=new WorkflowEvent(new EventMetadata(null,new EventName("order.approved"),"erp","corr",null,null,
                    null,null,null,"1",Instant.now(),Instant.now(),Map.of()),EventMessage.empty());
            WorkflowEventRoute route=WorkflowEventRoute.correlated("transactions","corr",approved.eventName()).fanOut();
            try(JdbcInboxApplication app=engine.inbox(message->List.of(new RouteWorkflowEventCommand(approved,route)))) {
                AtomicInteger snapshots=new AtomicInteger();
                engine.writeProbe(stage->{if(stage.equals("snapshot")&&snapshots.incrementAndGet()==1)throw new IllegalStateException("injected");});
                UUID id=app.accept(new InboxMessage(null,"external-f","erp",EventMessage.empty(),"corr",null,null,null,null,0,null,null,null,null)).message().messageId();

                app.pollOnce();
                assertEquals(InboxMessageStatus.RETRY_SCHEDULED,app.find(id).orElseThrow().status());
                assertEquals(1,completed(engine,first,second),"the healthy target must keep its delivery");

                engine.writeProbe(null);
                Thread.sleep(100);
                app.pollOnce();
                assertEquals(InboxMessageStatus.PROCESSED,app.find(id).orElseThrow().status());
                assertEquals(2,completed(engine,first,second),"the retry must reach the failed target");
            }
        }
    }

    @Test void timerLookupsAreIndexed() throws Exception {
        var source=source("index");
        JdbcWorkflowPersistence.create(null,null,null,null,source,true,Map.of());
        try(Connection connection=source.getConnection();Statement statement=connection.createStatement();
            ResultSet rows=statement.executeQuery("explain query plan select * from workflow_timer where workflow_instance_id='x'")) {
            StringBuilder plan=new StringBuilder();while(rows.next())plan.append(rows.getString("detail"));
            assertTrue(plan.toString().contains("idx_workflow_timer_instance"),plan.toString());
        }
    }

    private static long completed(JdbcWorkflowEngine engine,WorkflowInstanceId... ids){
        return Arrays.stream(ids).filter(id->engine.snapshot(id).status()==WorkflowStatus.COMPLETED).count();
    }
    private org.sqlite.SQLiteDataSource source(String name){
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(name+".db"));return source;
    }
}
