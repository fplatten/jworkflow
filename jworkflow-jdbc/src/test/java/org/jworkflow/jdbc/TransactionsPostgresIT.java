package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.persistence.*;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.TransactionNotificationContract.*;

@Timeout(120)
class TransactionsPostgresIT {
    static PostgresTestDatabase database;
    @BeforeAll static void start(){database=PostgresTestDatabase.start();}
    @AfterAll static void stop() throws Exception {if(database!=null)database.close();}

    @Test void nestedCommandsNotifyOnlyAfterOutermostCommitAndConnectionCleanup() throws Exception {
        try(var schema=database.createSchema()){nestedCommands(WorkflowEngine.Type.POSTGRESQL,schema.dataSource());}
    }
    @Test void everyCommandPersistenceStageRollsBack() throws Exception {
        try(var schema=database.createSchema()){persistenceStagesRollback(WorkflowEngine.Type.POSTGRESQL,schema.dataSource());}
    }
    @Test void inboxLaterCommandFailureDiscardsEarlierNotifications() throws Exception {
        try(var schema=database.createSchema()){inboxMultipleCommands(WorkflowEngine.Type.POSTGRESQL,schema.dataSource());}
    }

    @Test void failedAndAmbiguousCommitsDiscardCallbacksAndNeverReplayCommands() throws Exception {
        for(boolean committedBeforeFailure:List.of(false,true)) try(var schema=database.createSchema()) {
            TransactionTestDataSource source=new TransactionTestDataSource(schema.dataSource());
            AtomicInteger notified=new AtomicInteger(),writes=new AtomicInteger();
            try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.POSTGRESQL,source,event->notified.incrementAndGet())) {
                source.commitFailure=new SQLException("secret connection details","08006");
                source.commitBeforeFailure=committedBeforeFailure;
                engine.writeProbe(stage->{if(stage.equals("snapshot"))writes.incrementAndGet();});
                JdbcTransactionException failure=assertThrows(JdbcTransactionException.class,()->engine.transactionManager().inTransaction(()->engine.start(command("commit-failure"))));
                assertSame(source.commitFailure,failure.getCause()); assertEquals("commit",failure.phase());
                assertEquals(JdbcTransactionException.Category.CONNECTION,failure.category());
                assertFalse(failure.getMessage().contains("secret")); assertEquals(0,notified.get()); assertEquals(1,writes.get());
                assertEquals(committedBeforeFailure?1:0,count(schema.dataSource(),"workflow_instance"));
                assertEquals(source.borrowed.get(),source.closed.get());
                source.commitFailure=null;
                assertEquals("reusable",engine.transactionManager().inTransaction(()->"reusable"));
            }
        }
    }

    @Test void primaryFailuresRetainRollbackAndCleanupFailuresIncludingErrors() throws Exception {
        try(var schema=database.createSchema()) {
            TransactionTestDataSource source=new TransactionTestDataSource(schema.dataSource());
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,source,Map.of());
            var tx=new JdbcTransactionManager(factory);
            source.rollbackFailure=new SQLException("rollback failed","08006");
            source.closeFailure=new SQLException("close failed","08006");
            source.restoreFailure=new SQLException("must not enable auto commit after failed rollback");
            AssertionError original=new AssertionError("handler failure");
            assertSame(original,assertThrows(AssertionError.class,()->tx.inTransaction(()->{
                tx.afterCommit(()->fail("must discard"));
                throw original;
            })));
            assertArrayEquals(new Throwable[]{source.rollbackFailure,source.closeFailure},original.getSuppressed());
            assertNull(factory.currentTransactionConnection());
            source.rollbackFailure=null; source.closeFailure=null; source.restoreFailure=null;
            assertEquals(7,tx.inTransaction(()->7));
            // A successful rollback allows restoration, whose failures remain attached to the original.
            source.restoreFailure=new SQLException("restore failed"); source.closeFailure=new SQLException("close failed");
            IllegalStateException workFailure=new IllegalStateException("work");
            assertSame(workFailure,assertThrows(IllegalStateException.class,()->tx.inTransaction(()->{throw workFailure;})));
            assertSame(source.restoreFailure,workFailure.getSuppressed()[0]);
            assertSame(source.closeFailure,workFailure.getSuppressed()[0].getSuppressed()[0]);
        }
    }

    @Test void committedCleanupAndObserverFailuresAreReportedWithoutChangingResult() throws Exception {
        try(var schema=database.createSchema()) {
            var source=new TransactionTestDataSource(schema.dataSource());
            AtomicInteger invoked=new AtomicInteger();
            try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.POSTGRESQL,source,event->{invoked.incrementAndGet();throw new IllegalStateException("observer");})) {
                var tx=(JdbcTransactionManager)engine.transactionManager();
                List<Throwable> failures=new ArrayList<>(); tx.setCompletionFailureHandler(failures::add);
                source.closeFailure=new SQLException("close failed");
                List<Integer> order=new ArrayList<>();
                StartWorkflowResult result=tx.inTransaction(()->{
                    StartWorkflowResult started=engine.start(command("committed"));
                    tx.afterCommit(()->order.add(1));
                    tx.afterCommit(()->{throw new IllegalArgumentException("callback failure");});
                    tx.afterCommit(()->{assertNull(engine.connectionFactory().currentTransactionConnection());order.add(2);});
                    return started;
                });
                assertNotNull(result); assertEquals(1,count(schema.dataSource(),"workflow_instance"));
                assertTrue(invoked.get()>0); assertEquals(List.of(1,2),order);
                assertSame(source.closeFailure,failures.get(0)); assertEquals(invoked.get()+2,failures.size());
                source.closeFailure=null;
                tx.setCompletionFailureHandler(failure->{throw new IllegalStateException("broken diagnostic sink");});
                assertDoesNotThrow(()->tx.afterCommit(()->{throw new IllegalStateException("outside callback");}));
            }
        }
    }

    @Test void readCommittedSingleSessionNestedJoiningAndPooledPhysicalReuse() throws Exception {
        try(var schema=database.createSchema(); Connection physical=schema.openConnection()) {
            physical.setReadOnly(true); physical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            AtomicInteger returns=new AtomicInteger();
            DataSource pool=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
                if(!m.getName().equals("getConnection"))throw new UnsupportedOperationException();
                return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                    if(cm.getName().equals("close")){returns.incrementAndGet();return null;}
                    try{return cm.invoke(physical,ca);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
            });
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,pool,Map.of());
            var tx=new JdbcTransactionManager(factory);
            for(boolean rollback:List.of(false,true,false)) {
                Runnable work=()->tx.inTransaction(()->{
                    try(Connection first=factory.open(); Connection second=factory.open()) {
                        assertEquals(Connection.TRANSACTION_READ_COMMITTED,first.getTransactionIsolation());
                        assertFalse(first.isReadOnly()); assertFalse(first.getAutoCommit());
                        int pid=scalar(first,"select pg_backend_pid()"); assertEquals(pid,scalar(second,"select pg_backend_pid()"));
                        tx.inImmediateTransaction(()->{factory.requireImmediateTransaction("compatibility claim");return null;});
                        tx.afterCommit(()->{assertNull(factory.currentTransactionConnection());assertTrue(returns.get()>0);});
                        if(rollback)throw new IllegalStateException("rollback");
                    }catch(SQLException e){throw new AssertionError(e);} return null;
                });
                if(rollback)assertThrows(IllegalStateException.class,work::run);else work.run();
                assertTrue(physical.getAutoCommit()); assertTrue(physical.isReadOnly());
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ,physical.getTransactionIsolation());
                assertFalse(physical.isClosed());
            }
            assertEquals(3,returns.get());
        }
    }

    @Test void swallowedSqlErrorCannotProduceFalseCommitNotification() throws Exception {
        try(var schema=database.createSchema()) {
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,schema.dataSource(),Map.of());
            var tx=new JdbcTransactionManager(factory); AtomicInteger notified=new AtomicInteger();
            JdbcTransactionException failure=assertThrows(JdbcTransactionException.class,()->tx.inTransaction(()->{
                try(Connection connection=factory.open(); Statement statement=connection.createStatement()){
                    assertThrows(SQLException.class,()->statement.execute("select 1/0"));
                }catch(SQLException e){throw new AssertionError(e);}
                tx.afterCommit(notified::incrementAndGet);return null;
            }));
            assertEquals("25P02",((SQLException)failure.getCause()).getSQLState()); assertEquals(0,notified.get());
            tx.inTransaction(()->{tx.afterCommit(notified::incrementAndGet);return null;}); assertEquals(1,notified.get());
        }
    }

    @Test void inboxFinalizationStagesAndCommitFailureRollbackAllCommandsAndHistory() throws Exception {
        for(String stage:List.of("update workflow_inbox set status_value='PROCESSED'","insert into workflow_inbox_attempt","insert into event_status","commit","rollbackOnly"))
            try(var schema=database.createSchema()) {
                var source=new TransactionTestDataSource(schema.dataSource()); AtomicInteger notified=new AtomicInteger();
                try(JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.POSTGRESQL,source,event->notified.incrementAndGet())) {
                    var tx=(JdbcTransactionManager)engine.transactionManager();
                    var ports=JdbcWorkflowPersistence.from(engine.connectionFactory());
                    var input=StorageValueContract.inbox(EventMessage.empty(),Instant.now()); ports.inbox().insertIfAbsent(input);
                    var claimed=tx.inWriteTransaction(()->ports.inbox().claimEligible(Instant.now(),"owner",Instant.now().plusSeconds(60),1)).get(0);
                    var processor=new InboxProcessingService(ports.inbox(),ports.eventStatuses(),tx,m->List.of(command("inbox-write")),
                            c->{
                                var result=engine.start((StartWorkflowCommand)c);
                                if(stage.equals("rollbackOnly"))assertThrows(IllegalStateException.class,()->tx.inTransaction(()->{throw new IllegalStateException("nested");}));
                                return result;
                            },Clock.systemUTC());
                    if(stage.equals("commit")) source.commitFailure=new SQLException("commit failure","08006");
                    else if(!stage.equals("rollbackOnly")) source.failAfterSql=stage;
                    assertThrows(RuntimeException.class,()->tx.inWriteTransaction(()->processor.process(claimed,"owner")),stage);
                    assertEquals(0,notified.get());emptyCommands(schema.dataSource());
                    assertEquals(InboxMessageStatus.CLAIMED,ports.inbox().findById(input.messageId()).orElseThrow().status());
                    assertEquals(0,count(schema.dataSource(),"workflow_inbox_attempt"));assertEquals(0,count(schema.dataSource(),"event_status"));
                }
            }
    }

    @Test void deadlockLoserRollsBackWithoutReplayingWork() throws Exception {
        try(var schema=database.createSchema(); Connection setup=schema.openConnection(); Statement statement=setup.createStatement()) {
            statement.execute("create table transaction_probe(id integer primary key, value integer not null)");
            statement.execute("insert into transaction_probe values(1,0),(2,0)");
            CountDownLatch updated=new CountDownLatch(2); AtomicInteger executions=new AtomicInteger(),notifications=new AtomicInteger();
            ExecutorService executor=Executors.newFixedThreadPool(2);
            try {
                List<Future<Throwable>> results=new ArrayList<>();
                for(int first:List.of(1,2)) results.add(executor.submit(()->{
                    var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,schema.dataSource(),Map.of());
                    var tx=new JdbcTransactionManager(factory);
                    try {tx.inTransaction(()->{
                        executions.incrementAndGet();
                        try(Connection c=factory.open();Statement s=c.createStatement()) {
                            s.executeUpdate("update transaction_probe set value=value+1 where id="+first);
                            updated.countDown(); assertTrue(updated.await(10,TimeUnit.SECONDS));
                            s.executeUpdate("update transaction_probe set value=value+1 where id="+(3-first));
                        }catch(Exception failure){throw new WorkflowPersistenceException("probe",failure);}
                        tx.afterCommit(notifications::incrementAndGet);return null;
                    });return null;}catch(Throwable failure){return failure;}
                }));
                List<Throwable> failures=new ArrayList<>();for(var result:results){Throwable failure=result.get(20,TimeUnit.SECONDS);if(failure!=null)failures.add(failure);}
                assertEquals(1,failures.size());assertEquals(JdbcTransactionException.Category.DEADLOCK,JdbcTransactionException.classify(failures.get(0)));
                assertEquals(2,executions.get());assertEquals(1,notifications.get());
                assertEquals(2,scalar(setup,"select sum(value) from transaction_probe"));
            }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }

    private static int scalar(Connection connection,String sql) throws SQLException {
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(sql)){assertTrue(rows.next());return rows.getInt(1);}
    }

    @Test void inboxAndTimerWorkersQuarantineDeadlocksInsteadOfReplayingHandlers() throws Exception {
        try(var schema=database.createSchema();JdbcWorkflowEngine engine=engine(WorkflowEngine.Type.POSTGRESQL,schema.dataSource(),event->{})) {
            AtomicInteger writes=new AtomicInteger();
            engine.writeProbe(stage->{if(stage.equals("snapshot")){writes.incrementAndGet();throw new WorkflowPersistenceException("injected deadlock",new SQLException("deadlock","40P01"));}});
            try(var app=engine.inbox(message->List.of(command("quarantine-inbox")))) {
                var accepted=app.accept(StorageValueContract.inbox(EventMessage.empty(),Instant.now()));
                assertEquals(1,app.pollOnce());assertEquals(0,app.pollOnce());
                assertEquals(InboxMessageStatus.DEAD_LETTER,app.find(accepted.message().messageId()).orElseThrow().status());
                assertEquals(1,writes.get());emptyCommands(schema.dataSource());
            }
            engine.writeProbe(null);var started=engine.start(command("quarantine-timer"));
            try(Connection connection=schema.openConnection();Statement statement=connection.createStatement()) {statement.executeUpdate("update workflow_timer set due_at=0,next_attempt_at=0");}
            writes.set(0);
            engine.writeProbe(stage->{if(stage.equals("snapshot")){writes.incrementAndGet();throw new WorkflowPersistenceException("injected deadlock",new SQLException("deadlock","40P01"));}});
            assertThrows(WorkflowPersistenceException.class,engine::pollTimersOnce);assertEquals(0,engine.pollTimersOnce());
            assertEquals(1,writes.get());assertEquals(1,count(schema.dataSource(),"workflow_timer where status_value='DEAD_LETTER'"));
            assertEquals("waiting",engine.snapshot(started.workflowInstanceId()).state());
        }
    }

    @Test void deferredOutboxPublicationAndObservationsFollowOuterOutcome() throws Exception {
        try(var schema=database.createSchema()) {
            var ports=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            var tx=ports.jdbcTransactions();
            AtomicInteger observations=new AtomicInteger(),publications=new AtomicInteger();
            List<Throwable> callbackFailures=new ArrayList<>();tx.setCompletionFailureHandler(callbackFailures::add);
            var message=StorageValueContract.outbox(EventMessage.empty(),Instant.now());ports.outbox().enqueue(message);
            var claimed=tx.inWriteTransaction(()->ports.outbox().claimEligible(Instant.now(),"owner",Instant.now().plusSeconds(60),1)).get(0);
            var publisher=new org.jworkflow.outbox.OutboxPublisherService(ports.outbox(),ports.eventStatuses(),tx,
                    sent->publications.incrementAndGet(),new org.jworkflow.application.RetryBackoffPolicy(){
                        public boolean exhausted(int attempt){return attempt>=3;}
                        public Instant nextAttemptAt(int attempt,Instant now){return now.plusSeconds(1);}
                    },Clock.systemUTC(),
                    event->{assertEquals(1,count(schema.dataSource(),"workflow_outbox where status_value='PUBLISHED'"));observations.incrementAndGet();});
            assertThrows(IllegalStateException.class,()->tx.inTransaction(()->{
                tx.afterCommit(()->publisher.publish(claimed,"owner"));assertEquals(0,observations.get());throw new IllegalStateException("outer");
            }));
            assertEquals(0,observations.get());assertEquals(0,count(schema.dataSource(),"workflow_outbox_attempt"));
            assertEquals(0,publications.get());
            tx.inTransaction(()->{tx.afterCommit(()->publisher.publish(claimed,"owner"));assertEquals(0,observations.get());assertEquals(0,publications.get());return null;});
            assertEquals(1,observations.get());assertTrue(callbackFailures.isEmpty());
            assertEquals(1,publications.get(),"publication runs only outside the committed outer transaction");
        }
    }

    @Test void relationalIdempotencyKeysPreserveInboxNulAndLiteralEscapeSequences() throws Exception {
        try(var schema=database.createSchema()) {
            var ports=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            for(String key:List.of("source\u0000external","source%00external","source%25external","Unicode-\u96ea%00\u0000")) {
                var record=new CommandResultRecord(key,"start","hash",null,Map.of("key",key),Instant.now());
                ports.commandResults().save(record);assertEquals(record,ports.commandResults().find(key).orElseThrow());
                var eventId=UUID.randomUUID();
                var status=new EventStatusAttempt(null,eventId,null,1,key,null,null,EventStatusScope.INBOX,"processor","",EventStatusValue.SUCCESSFUL,0,null,false,false,null,null,Instant.now());
                ports.eventStatuses().append(status);
                assertEquals(key,ports.eventStatuses().findAttempts(eventId).get(0).idempotencyKey());
                var original=StorageValueContract.outbox(EventMessage.empty(),Instant.now());
                var outbox=new org.jworkflow.outbox.OutboxMessage(original.messageId(),original.eventId(),original.destination(),key,original.message(),original.correlationId(),original.causationId(),original.createdAt(),null,original.status(),0,null,null,null,null);
                ports.outbox().enqueue(outbox);assertEquals(outbox,ports.outbox().findByIdempotencyKey(outbox.destination(),key).orElseThrow());
            }
            assertEquals(4,count(schema.dataSource(),"workflow_command_result"));
        }
    }

    @Test void reentrantCallbacksPreserveRegistrationOrderAndMayOpenFreshTransactions() throws Exception {
        try(var schema=database.createSchema()) {
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,schema.dataSource(),Map.of());
            var tx=new JdbcTransactionManager(factory);List<Integer> order=new ArrayList<>();List<Throwable> failures=new ArrayList<>();
            tx.setCompletionFailureHandler(failures::add);
            tx.inTransaction(()->{
                tx.afterCommit(()->{
                    order.add(1);
                    tx.inTransaction(()->{tx.afterCommit(()->order.add(3));return null;});
                    tx.afterCommit(()->order.add(4));
                });
                tx.afterCommit(()->order.add(2));return null;
            });
            assertEquals(List.of(1,2,3,4),order);assertTrue(failures.isEmpty());
        }
    }

    @Test void hostTransactionIsRejectedWithoutCommitRollbackOrStateChanges() throws Exception {
        try(var schema=database.createSchema();Connection physical=schema.openConnection()) {
            try(Statement statement=physical.createStatement()){statement.execute("create table host_probe(id integer)");}
            physical.setAutoCommit(false);
            try(Statement statement=physical.createStatement()){statement.executeUpdate("insert into host_probe values(1)");}
            AtomicInteger returned=new AtomicInteger();
            DataSource host=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
                if(!m.getName().equals("getConnection"))throw new UnsupportedOperationException();
                return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                    if(cm.getName().equals("close")){returned.incrementAndGet();return null;}
                    try{return cm.invoke(physical,ca);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
            });
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,host,Map.of());
            var tx=new JdbcTransactionManager(factory);
            assertThrows(JdbcTransactionException.class,()->tx.inTransaction(()->{fail("host work cannot be enlisted");return null;}));
            assertFalse(physical.getAutoCommit());assertEquals(1,scalar(physical,"select count(*) from host_probe"));
            assertEquals(0,count(schema.dataSource(),"host_probe"));assertEquals(1,returned.get());
            physical.rollback();physical.setAutoCommit(true);
            assertEquals(1,tx.inTransaction(()->1));
        }
    }

    @Test void realStatementTimeoutIsClassifiedAndTransactionCanBeReused() throws Exception {
        try(var schema=database.createSchema()) {
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,schema.dataSource(),Map.of());
            var tx=new JdbcTransactionManager(factory);AtomicInteger runs=new AtomicInteger(),callbacks=new AtomicInteger();
            WorkflowPersistenceException failure=assertThrows(WorkflowPersistenceException.class,()->tx.inTransaction(()->{
                runs.incrementAndGet();tx.afterCommit(callbacks::incrementAndGet);
                try(Connection connection=factory.open();Statement statement=connection.createStatement()) {
                    statement.execute("set local statement_timeout='50ms'");statement.execute("select pg_sleep(1)");
                }catch(SQLException problem){throw new WorkflowPersistenceException("timeout test",problem);}return null;
            }));
            assertEquals(JdbcTransactionException.Category.TIMEOUT,JdbcTransactionException.classify(failure));
            assertEquals(1,runs.get());assertEquals(0,callbacks.get());
            assertEquals("healthy",tx.inTransaction(()->"healthy"));
        }
    }

    @Test void failedQuarantinePausesWorkersAndPreservesOriginalFailure() throws Exception {
        try(var schema=database.createSchema()) {
            var source=new TransactionTestDataSource(schema.dataSource());
            try(var engine=engine(WorkflowEngine.Type.POSTGRESQL,source,event->{})) {
                var original=new WorkflowPersistenceException("deadlock",new SQLException("deadlock","40P01"));
                engine.writeProbe(stage->{if(stage.equals("snapshot"))throw original;});
                try(var app=engine.inbox(message->List.of(command("failed-quarantine")))) {
                    app.accept(StorageValueContract.inbox(EventMessage.empty(),Instant.now()));
                    source.failAfterSql="insert into workflow_inbox_attempt";
                    assertSame(original,assertThrows(WorkflowPersistenceException.class,app::pollOnce));
                    assertEquals(1,original.getSuppressed().length);
                    source.failAfterSql=null;engine.writeProbe(null);
                    assertTrue(assertThrows(org.jworkflow.engine.WorkflowInfrastructureException.class,app::pollOnce).getMessage().contains("paused"));
                    emptyCommands(schema.dataSource());
                }
                engine.start(command("failed-timer-quarantine"));
                try(Connection connection=schema.openConnection();Statement statement=connection.createStatement()){statement.executeUpdate("update workflow_timer set due_at=0,next_attempt_at=0");}
                engine.writeProbe(stage->{if(stage.equals("snapshot"))throw original;});source.failAfterSql="insert into workflow_timer_attempt";
                assertSame(original,assertThrows(WorkflowPersistenceException.class,engine::pollTimersOnce));
                assertEquals(2,original.getSuppressed().length);
                source.failAfterSql=null;engine.writeProbe(null);
                assertTrue(assertThrows(org.jworkflow.engine.WorkflowInfrastructureException.class,engine::pollTimersOnce).getMessage().contains("paused"));
            }
        }
    }
}
