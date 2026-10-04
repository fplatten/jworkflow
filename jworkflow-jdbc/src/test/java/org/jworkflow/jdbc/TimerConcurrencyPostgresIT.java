package org.jworkflow.jdbc;
import org.jworkflow.internal.model.WorkflowTimer;

import org.jworkflow.engine.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.TransactionNotificationContract.*;

/** Timer pollers and commands touching the same workflow must neither deadlock nor fail each other spuriously. */
@Timeout(120)
class TimerConcurrencyPostgresIT {
    static PostgresTestDatabase database;
    @BeforeAll static void start(){database=PostgresTestDatabase.start();}
    @AfterAll static void stop() throws Exception {if(database!=null)database.close();}

    @Test void commandSucceedsWhenPollerClaimsTimerMidTransaction() throws Exception {
        try(var schema=database.createSchema();JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.POSTGRESQL,schema.dataSource(),event->{})) {
            WorkflowInstanceId id=engine.start(command("race")).workflowInstanceId();
            var poller=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),false,Map.of());
            Instant future=Instant.now().plus(Duration.ofHours(2));
            List<WorkflowTimer> claimed=new ArrayList<>();
            // After the command has read the timer as PENDING, another poller claims it and commits.
            engine.writeProbe(stage->{if(stage.equals("snapshot")&&claimed.isEmpty())
                claimed.addAll(poller.jdbcTransactions().inWriteTransaction(()->poller.timers().claimDueFenced(future,"other-poller",future.plusSeconds(60),10)));});

            engine.signal(approve(id,"race"));

            assertEquals(1,claimed.size(),"poller must have claimed the timer during the command");
            assertEquals(WorkflowStatus.COMPLETED,engine.snapshot(id).status());
            assertEquals("CANCELED",timerStatus(schema));
        }
    }

    @Test void timerFiringAndCommandOnSameWorkflowDoNotDeadlock() throws Exception {
        try(var schema=database.createSchema()) {
            var source=new TransactionTestDataSource(schema.dataSource());
            try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.POSTGRESQL,source,event->{})) {
                WorkflowInstanceId id=engine.start(command("deadlock")).workflowInstanceId();
                try(Connection connection=schema.openConnection();Statement statement=connection.createStatement()){statement.executeUpdate("update workflow_timer set due_at=0,next_attempt_at=0");}
                CountDownLatch timerLocked=new CountDownLatch(1),release=new CountDownLatch(1);
                // Hold the poller right after it locks its claimed timer row.
                source.afterUpdate=sql->{if(sql.contains("workflow_timer")&&sql.contains("claim_token=claim_token")){
                    timerLocked.countDown();
                    try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                }};
                ExecutorService executor=Executors.newFixedThreadPool(2);
                try {
                    Future<Integer> poll=executor.submit(engine::pollTimersOnce);
                    assertTrue(timerLocked.await(30,TimeUnit.SECONDS));
                    Future<WorkflowCommandResult> signal=executor.submit(()->engine.signal(approve(id,"deadlock")));
                    Thread.sleep(500);
                    release.countDown();

                    assertEquals(1,poll.get(30,TimeUnit.SECONDS));
                    ExecutionException failure=assertThrows(ExecutionException.class,()->signal.get(30,TimeUnit.SECONDS));
                    assertNotEquals(JdbcTransactionException.Category.DEADLOCK,JdbcTransactionException.classify(failure.getCause()));
                    assertInstanceOf(WorkflowOptimisticLockException.class,failure.getCause(),
                            "the command must lose a plain optimistic-lock race the caller can retry");
                }finally{source.afterUpdate=null;release.countDown();executor.shutdownNow();}
                assertEquals("FIRED",timerStatus(schema));
                assertEquals("done",engine.snapshot(id).state());
            }
        }
    }

    private static SignalWorkflowCommand approve(WorkflowInstanceId id,String key){
        return new SignalWorkflowCommand(id,new WorkflowSignal("order.approved","corr",null,key,Instant.now(),Map.of()),null);
    }
    private static String timerStatus(PostgresTestDatabase.Schema schema) throws SQLException {
        try(Connection connection=schema.openConnection();Statement statement=connection.createStatement();
            ResultSet rows=statement.executeQuery("select status_value from workflow_timer")){
            assertTrue(rows.next());String status=rows.getString(1);assertFalse(rows.next());return status;
        }
    }
}
