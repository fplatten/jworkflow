package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.TransactionNotificationContract.*;

/** Obsolete timers are canceled, and one failing item or a JVM Error never stops a worker. */
class WorkerResilienceTest {
    private static final String LONG_AGO="2000-01-01T00:00:00Z";
    @TempDir Path directory;

    @Test void leavingStepCancelsTimerAwaitingRetry() throws Exception {
        var source=source("retry-cancel");
        try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.SQLITE,source,event->{})) {
            WorkflowInstanceId id=engine.start(command("a")).workflowInstanceId();
            update(source,"update workflow_timer set status_value='RETRY_SCHEDULED'");
            engine.signal(approve(id,"a"));
            assertEquals(WorkflowStatus.COMPLETED,engine.snapshot(id).status());
            assertEquals(List.of("CANCELED"),timerStatuses(source));
        }
    }

    @Test void obsoleteTimerIsCanceledInsteadOfRetriedForever() throws Exception {
        var source=source("obsolete");
        try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.SQLITE,source,event->{})) {
            WorkflowInstanceId id=engine.start(command("b")).workflowInstanceId();
            engine.signal(approve(id,"b"));
            // An orphan left behind by an older release or a lost race.
            update(source,"update workflow_timer set status_value='PENDING',due_at='"+LONG_AGO+"',next_attempt_at=null");
            assertEquals(1,engine.pollTimersOnce());
            assertEquals(List.of("CANCELED"),timerStatuses(source));
            assertEquals(0,engine.pollTimersOnce());
            assertEquals(WorkflowStatus.COMPLETED,engine.snapshot(id).status());
        }
    }

    @Test void failingTimerDoesNotStrandRestOfBatch() throws Exception {
        var source=source("timer-batch");
        try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.SQLITE,source,event->{})) {
            engine.start(command("c1"));engine.start(command("c2"));
            update(source,"update workflow_timer set due_at='"+LONG_AGO+"',next_attempt_at=null");
            AtomicInteger snapshots=new AtomicInteger();
            engine.writeProbe(stage->{if(stage.equals("snapshot")&&snapshots.incrementAndGet()==1)throw new IllegalStateException("injected");});
            assertThrows(IllegalStateException.class,engine::pollTimersOnce);
            List<String> statuses=timerStatuses(source);
            assertTrue(statuses.contains("FIRED"),"second timer must still fire: "+statuses);
            assertTrue(statuses.contains("RETRY_SCHEDULED"),statuses.toString());
            assertFalse(statuses.contains("CLAIMED"),statuses.toString());
        }
    }

    @Test void failingInboxMessageDoesNotStrandRestOfBatch() throws Exception {
        var source=new TransactionTestDataSource(source("inbox-batch"));
        try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.SQLITE,source,event->{});
            JdbcInboxApplication app=engine.inbox(WorkerResilienceTest::startFor)) {
            UUID first=app.accept(message("m1")).message().messageId(),second=app.accept(message("m2")).message().messageId();
            AtomicInteger snapshots=new AtomicInteger(),attempts=new AtomicInteger();
            // The first message fails, and so does recording its retry, which is what used to abort the batch.
            engine.writeProbe(stage->{if(stage.equals("snapshot")&&snapshots.incrementAndGet()==1)throw new IllegalStateException("injected");});
            source.afterUpdate=sql->{if(sql.contains("insert into workflow_inbox_attempt")&&attempts.incrementAndGet()==1)throw new IllegalStateException("injected finalization");};
            assertThrows(IllegalStateException.class,app::pollOnce);
            Map<InboxMessageStatus,Integer> statuses=new EnumMap<>(InboxMessageStatus.class);
            for(UUID id:List.of(first,second))statuses.merge(app.find(id).orElseThrow().status(),1,Integer::sum);
            assertEquals(Map.of(InboxMessageStatus.CLAIMED,1,InboxMessageStatus.PROCESSED,1),statuses,
                    "the second message must be processed even though the first one's failure could not be recorded");
        }
    }

    @Test void scheduledInboxPollerSurvivesJvmError() throws Exception {
        var source=source("inbox-error");
        AtomicInteger calls=new AtomicInteger();
        InboxEventTranslator translator=message->{
            if(calls.incrementAndGet()==1)throw new NoClassDefFoundError("injected");
            return startFor(message);
        };
        try(JdbcWorkflowEngine engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).dataSource(source)
                .initialize(true).timerPolling(false).definition(definition())
                .setting("inbox.poll-interval-ms","10").setting("inbox.lease-ms","100").build();
            JdbcInboxApplication app=engine.inbox(translator)) {
            UUID first=app.accept(message("e1")).message().messageId();
            app.start();
            long deadline=System.nanoTime()+Duration.ofSeconds(1).toNanos();
            while(calls.get()==0&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(calls.get()>0,"poller never ran");
            UUID second=app.accept(message("e2")).message().messageId();
            deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(System.nanoTime()<deadline&&(app.find(first).orElseThrow().status()!=InboxMessageStatus.PROCESSED
                    ||app.find(second).orElseThrow().status()!=InboxMessageStatus.PROCESSED))Thread.sleep(20);
            assertEquals(InboxMessageStatus.PROCESSED,app.find(second).orElseThrow().status(),"poller must keep running after an Error");
            assertEquals(InboxMessageStatus.PROCESSED,app.find(first).orElseThrow().status(),"expired claim must be retried");
        }
    }

    private org.sqlite.SQLiteDataSource source(String name){
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(name+".db"));return source;
    }
    private static SignalWorkflowCommand approve(WorkflowInstanceId id,String key){
        return new SignalWorkflowCommand(id,new WorkflowSignal("order.approved","corr",null,key,Instant.now(),Map.of()),null);
    }
    private static List<org.jworkflow.application.Command> startFor(InboxMessage message){
        return List.of(command((String)((Map<?,?>)message.message().payload()).get("businessKey")));
    }
    private static InboxMessage message(String key){
        return new InboxMessage(null,"external-"+key,"test",EventMessage.json(Map.of("businessKey",key)),"corr-"+key,null,null,null,null,0,null,null,null,null);
    }
    private static void update(javax.sql.DataSource source,String sql) throws SQLException {
        try(Connection connection=source.getConnection();Statement statement=connection.createStatement()){statement.executeUpdate(sql);}
    }
    private static List<String> timerStatuses(javax.sql.DataSource source) throws SQLException {
        List<String> result=new ArrayList<>();
        try(Connection connection=source.getConnection();Statement statement=connection.createStatement();
            ResultSet rows=statement.executeQuery("select status_value from workflow_timer order by id")){while(rows.next())result.add(rows.getString(1));}
        return result;
    }
}
