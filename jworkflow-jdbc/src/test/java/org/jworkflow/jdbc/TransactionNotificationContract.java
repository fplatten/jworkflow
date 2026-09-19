package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class TransactionNotificationContract {
    static WorkflowDefinition definition() {
        return WorkflowDefinition.of("transactions", "1", "waiting",
                WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("order.approved"),"businessKey","done"),
                        new TimeoutDefinition(Duration.ofHours(1),"done",null)), WorkflowNode.end("done"));
    }

    static StartWorkflowCommand command(String key) {
        return new StartWorkflowCommand("transactions","1",key,Map.of(),
                new WorkflowCommandMetadata(null,key,"transactions","1",null,key,"corr",null,null,null,"test",null,null,Map.of()));
    }

    static JdbcWorkflowEngine engine(WorkflowEngine.Type type, DataSource source, EventPublisher publisher) {
        try { return (JdbcWorkflowEngine) WorkflowEngine.builder().type(type).dataSource(source).initialize(true)
                .timerPolling(false).definition(definition()).eventPublisher(publisher).build();
        } catch(ClassNotFoundException failure) { throw new AssertionError(failure); }
    }

    static int count(DataSource source,String table) {
        try(Connection connection=source.getConnection(); Statement statement=connection.createStatement();
                ResultSet rows=statement.executeQuery("select count(*) from "+table)) {
            assertTrue(rows.next()); return rows.getInt(1);
        } catch(SQLException failure) { throw new AssertionError(failure); }
    }

    static void emptyCommands(DataSource source) {
        for(String table:List.of("workflow_instance","workflow_event","workflow_timer","workflow_outbox","workflow_command_result"))
            assertEquals(0,count(source,table),table);
    }

    static void nestedCommands(WorkflowEngine.Type type, DataSource source) {
        List<WorkflowEvent> observed=new ArrayList<>();
        TransactionTestDataSource tracked=new TransactionTestDataSource(source);
        List<Throwable> callbackFailures=new ArrayList<>();
        try(JdbcWorkflowEngine engine=engine(type,tracked,event->{
            assertEquals(tracked.borrowed.get(),tracked.closed.get(),"borrowed connection must be returned first");
            assertEquals(2,count(source,"workflow_instance"),"independent session sees both commands");
            observed.add(event);
        })) {
            JdbcTransactionManager tx=(JdbcTransactionManager)engine.transactionManager();
            tx.setCompletionFailureHandler(callbackFailures::add);
            List<Integer> order=new ArrayList<>();
            tx.inWriteTransaction(()->{
                engine.start(command("first"));
                tx.afterCommit(()->order.add(1));
                tx.inTransaction(()->{engine.start(command("second"));tx.afterCommit(()->order.add(2));return null;});
                assertTrue(observed.isEmpty()); assertTrue(order.isEmpty());
                assertEquals(0,count(source,"workflow_instance"));
                return null;
            });
            assertFalse(observed.isEmpty()); assertEquals(List.of(1,2),order); assertTrue(callbackFailures.isEmpty());
            int before=observed.size();
            RuntimeException injected=new IllegalStateException("outer rollback");
            assertSame(injected,assertThrows(IllegalStateException.class,()->tx.inWriteTransaction(()->{
                engine.start(command("rolled-back"));throw injected;
            })));
            assertEquals(before,observed.size()); assertEquals(2,count(source,"workflow_instance"));
            WorkflowPersistenceException rollbackOnly=assertThrows(WorkflowPersistenceException.class,()->tx.inWriteTransaction(()->{
                engine.start(command("rollback-only"));
                assertThrows(IllegalStateException.class,()->tx.inTransaction(()->{throw injected;}));
                return null;
            }));
            assertSame(injected,rollbackOnly.getCause()); assertEquals(before,observed.size());
            assertEquals(2,count(source,"workflow_instance"));
            tx.afterCommit(()->order.add(3)); assertEquals(List.of(1,2,3),order);
        }
    }

    static void persistenceStagesRollback(WorkflowEngine.Type type,DataSource source) {
        AtomicInteger observed=new AtomicInteger();
        try(JdbcWorkflowEngine engine=engine(type,source,event->observed.incrementAndGet())) {
            for(String stage:List.of("snapshot","event","outbox","timer","commandResult")) {
                AtomicInteger reached=new AtomicInteger();
                engine.writeProbe(current->{if(current.equals(stage)){reached.incrementAndGet();throw new IllegalStateException(stage);}});
                assertThrows(IllegalStateException.class,()->engine.start(command(stage)));
                assertEquals(1,reached.get(),"failure point must execute: "+stage);
                emptyCommands(source); assertEquals(0,observed.get());
            }
        }
    }

    static void inboxMultipleCommands(WorkflowEngine.Type type,DataSource source) {
        List<WorkflowEvent> observed=new ArrayList<>();
        try(JdbcWorkflowEngine engine=engine(type,source,event->{
            assertEquals(1,count(source,"workflow_inbox where status_value='PROCESSED'"));
            observed.add(event);
        })) {
            JdbcTransactionManager tx=(JdbcTransactionManager)engine.transactionManager();
            List<Throwable> callbackFailures=new ArrayList<>(); tx.setCompletionFailureHandler(callbackFailures::add);
            var ports=JdbcWorkflowPersistence.from(engine.connectionFactory());
            InboxMessage input=StorageValueContract.inbox(EventMessage.empty(),Instant.now());
            ports.inbox().insertIfAbsent(input);
            InboxMessage claimed=tx.inWriteTransaction(()->ports.inbox().claimEligible(Instant.now(),"owner",Instant.now().plusSeconds(60),1)).get(0);
            AtomicInteger dispatched=new AtomicInteger();
            InboxProcessingService processor=new InboxProcessingService(ports.inbox(),ports.eventStatuses(),tx,
                    message->List.of(command("inbox-first"),command("inbox-second")), command->{
                        StartWorkflowResult result=engine.start((StartWorkflowCommand)command);
                        assertTrue(observed.isEmpty());
                        if(dispatched.incrementAndGet()==2)throw new IllegalStateException("later command failed");
                        return result;
                    },Clock.systemUTC());
            assertThrows(IllegalStateException.class,()->tx.inWriteTransaction(()->processor.process(claimed,"owner")));
            emptyCommands(source); assertTrue(observed.isEmpty());
            assertEquals(InboxMessageStatus.CLAIMED,ports.inbox().findById(input.messageId()).orElseThrow().status());
            assertEquals(0,count(source,"workflow_inbox_attempt")); assertEquals(0,count(source,"event_status"));
            // The next run succeeds; callbacks see the inbox finalization, not only workflow writes.
            tx.inWriteTransaction(()->processor.process(claimed,"owner"));
            assertEquals(InboxMessageStatus.PROCESSED,ports.inbox().findById(input.messageId()).orElseThrow().status());
            assertEquals(2,count(source,"workflow_instance")); assertFalse(observed.isEmpty());
            assertTrue(callbackFailures.isEmpty());
            assertEquals(1,count(source,"workflow_inbox_attempt"));
        }
    }
}
