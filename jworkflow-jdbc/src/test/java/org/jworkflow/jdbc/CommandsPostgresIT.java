package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import org.jworkflow.routing.*;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.TransactionNotificationContract.command;
import static org.jworkflow.jdbc.TransactionNotificationContract.count;

@Timeout(90)
class CommandsPostgresIT {
    static PostgresTestDatabase database;
    static final Instant NOW=Instant.parse("2026-09-18T12:00:00.123456789Z");
    @BeforeAll static void start(){database=PostgresTestDatabase.start();}
    @AfterAll static void stop()throws Exception{if(database!=null)database.close();}

    static JdbcWorkflowEngine engine(DataSource source,Runnable started,Runnable advanced,Map<String,String> settings)throws Exception {
        var initial=new WorkflowNode("waiting",WorkflowNodeType.STEP,"finish.action",null,null,null,null,null,null,null,null,null,
                new TimeoutDefinition(Duration.ofHours(1),"done",null),List.of(new WorkflowTransition(null,"done",null,new EventName("order.approved"))));
        var definition=WorkflowDefinition.of("transactions","1","waiting",initial,WorkflowNode.end("done"));
        var builder=WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(source).initialize(true).timerPolling(false)
                .definition(definition)
                .stepHandler("finish.action",context->{advanced.run();return StepResult.success();});
        settings.forEach(builder::setting);
        var engine=(JdbcWorkflowEngine)builder.build();
        // MVP start creates a snapshot and timer; step handlers run when a signal arrives.
        engine.writeProbe(stage->{if(stage.equals("snapshot"))started.run();});
        return engine;
    }
    static JdbcWorkflowEngine engine(DataSource source,Runnable started)throws Exception{return engine(source,started,()->{},Map.of());}
    static JdbcTransactionManager tx(JdbcWorkflowEngine engine){return (JdbcTransactionManager)engine.transactionManager();}
    static SignalWorkflowCommand signal(WorkflowInstanceId id,String key,EventMessage message){
        return new SignalWorkflowCommand(id,new WorkflowSignal("order.approved","corr",null,"same",NOW,Map.of(),message),command(key).metadata());
    }

    @TestFactory Stream<DynamicTest> startsAreProtectedThroughOutermostCommit(){
        return Stream.of("matching","payload","type","rollback","nul-key").map(mode->DynamicTest.dynamicTest(mode,()->startRace(mode)));
    }
    void startRace(String mode)throws Exception {
        try(var schema=database.createSchema()) {
            var observed=new DuplicatesPostgresIT.ObservedSource(schema.dataSource());
            AtomicInteger starts=new AtomicInteger();
            try(var first=engine(schema.dataSource(),starts::incrementAndGet);var second=engine(observed.source,starts::incrementAndGet)) {
                String key=mode.equals("nul-key")?"key\u0000%雪":"same";
                var original=new StartWorkflowCommand("transactions","1","same",Map.of(),command(key).metadata());
                var different=new StartWorkflowCommand(original.workflowKey(),original.workflowVersion(),original.businessKey(),Map.of("different",true),original.metadata());
                var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var firstResult=new AtomicReference<StartWorkflowResult>();
                ExecutorService executor=Executors.newFixedThreadPool(2);
                try {
                    Future<?> holder=executor.submit(()->{
                        try {tx(first).inWriteTransaction(()->{firstResult.set(first.start(original));ready.countDown();await(release);
                            if(mode.equals("rollback"))throw new Abort();return null;});}catch(Abort expected){}
                    });
                    await(ready);
                    Future<Object> contender=executor.submit(()->{
                        try {
                            if(mode.equals("type"))return second.cancel(new CancelWorkflowCommand(firstResult.get().workflowInstanceId(),original.metadata()));
                            return second.start(mode.equals("payload")?different:original);
                        }catch(WorkflowIdempotencyConflictException failure){return failure;}
                    });
                    awaitBlocked(schema.dataSource(),observed.application);
                    assertEquals(1,starts.get(),"loser must not mutate before the outer commit");
                    assertEquals(0,count(schema.dataSource(),"workflow_instance"));
                    release.countDown();holder.get(15,TimeUnit.SECONDS);Object result=contender.get(15,TimeUnit.SECONDS);
                    if(mode.equals("payload")||mode.equals("type"))assertInstanceOf(WorkflowIdempotencyConflictException.class,result);
                    else {
                        var returned=assertInstanceOf(StartWorkflowResult.class,result);
                        if(mode.equals("rollback")){assertFalse(returned.idempotentRepeat());assertNotEquals(firstResult.get().workflowInstanceId(),returned.workflowInstanceId());}
                        else assertEquals(firstResult.get().asIdempotentRepeat(),returned);
                    }
                    assertEquals(mode.equals("rollback")?2:1,starts.get());
                    assertEquals(1,count(schema.dataSource(),"workflow_instance"));assertEquals(1,count(schema.dataSource(),"workflow_command_result"));
                    assertEquals(1,count(schema.dataSource(),"workflow_timer"));assertEquals(1,count(schema.dataSource(),"workflow_event_sequence"));
                    assertEquals(firstResult.get().emittedEventIds().size(),count(schema.dataSource(),"workflow_event"));
                    assertEquals(count(schema.dataSource(),"workflow_event"),count(schema.dataSource(),"workflow_outbox"));
                    assertSequences(schema.dataSource());
                }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
            }
        }
    }

    @Test void distinctKeysRetainOptimisticLockingAndRollbackAllLoserWrites()throws Exception {
        try(var schema=database.createSchema()) {
            CyclicBarrier handlers=new CyclicBarrier(2);AtomicInteger attempts=new AtomicInteger();
            Runnable advance=()->{attempts.incrementAndGet();barrier(handlers);};
            try(var first=engine(schema.dataSource(),()->{},advance,Map.of());var second=engine(schema.dataSource(),()->{},advance,Map.of())) {
                var started=first.start(command("same"));
                ExecutorService executor=Executors.newFixedThreadPool(2);
                try {
                    var a=executor.submit(()->outcome(()->first.signal(signal(started.workflowInstanceId(),"a",EventMessage.empty()))));
                    var b=executor.submit(()->outcome(()->second.signal(signal(started.workflowInstanceId(),"b",EventMessage.empty()))));
                    var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
                    assertEquals(1,results.stream().filter(WorkflowOptimisticLockException.class::isInstance).count());
                    var winner=(WorkflowCommandResult)results.stream().filter(WorkflowCommandResult.class::isInstance).findFirst().orElseThrow();
                    assertEquals(2,attempts.get());assertEquals(2,count(schema.dataSource(),"workflow_command_result"));
                    // Start plus one step.entered/step.completed/transition.taken/workflow.completed set.
                    assertEquals(5,count(schema.dataSource(),"workflow_event"));
                    assertEquals(1,count(schema.dataSource(),"workflow_timer"));
                    assertEquals(count(schema.dataSource(),"workflow_event"),count(schema.dataSource(),"workflow_outbox"));
                    assertEquals(winner.snapshot(),first.snapshot(started.workflowInstanceId()));assertSequences(schema.dataSource());
                }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
            }
        }
    }

    @Test void reverseMultiCommandOrderDeadlocksBoundedlyWithoutPartialCommitOrAutomaticReplay()throws Exception {
        try(var schema=database.createSchema()) {
            AtomicInteger handlers=new AtomicInteger();CyclicBarrier held=new CyclicBarrier(2);
            try(var first=engine(schema.dataSource(),handlers::incrementAndGet);var second=engine(schema.dataSource(),handlers::incrementAndGet)) {
                ExecutorService executor=Executors.newFixedThreadPool(2);
                try {
                    var a=executor.submit(()->twoKeys(first,"a","b",held));var b=executor.submit(()->twoKeys(second,"b","a",held));
                    var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
                    assertEquals(1,results.stream().filter(v->v==Boolean.TRUE).count());
                    Throwable failure=(Throwable)results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
                    assertEquals(JdbcTransactionException.Category.DEADLOCK,JdbcTransactionException.classify(failure));
                    assertEquals(3,handlers.get(),"two first attempts plus survivor's second command, no automatic replay");
                    assertEquals(2,count(schema.dataSource(),"workflow_instance"));assertEquals(2,count(schema.dataSource(),"workflow_command_result"));
                    assertEquals(2,count(schema.dataSource(),"workflow_timer"));assertEquals(2,count(schema.dataSource(),"workflow_event_sequence"));
                    assertEquals(count(schema.dataSource(),"workflow_event"),count(schema.dataSource(),"workflow_outbox"));assertSequences(schema.dataSource());
                }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
            }
        }
    }
    static Object twoKeys(JdbcWorkflowEngine engine,String first,String second,CyclicBarrier held){
        return outcome(()->tx(engine).inWriteTransaction(()->{engine.start(command(first));barrier(held);engine.start(command(second));return Boolean.TRUE;}));
    }

    @Test void boundedKeyTimeoutPreservesCauseDoesNotExecuteHandlerAndRestoresHostSetting()throws Exception {
        try(var schema=database.createSchema();Connection holder=schema.openConnection()) {
            AtomicInteger handlers=new AtomicInteger();
            try(var engine=engine(schema.dataSource(),handlers::incrementAndGet,()->{},Map.of("postgres.command-lock-timeout-ms","150"))) {
                holder.setAutoCommit(false);new PostgresqlDatabaseStrategy(Map.of()).lockCommand(holder,"blocked");
                long begin=System.nanoTime();var failure=assertThrows(JdbcTransactionException.class,()->engine.start(command("blocked")));
                assertEquals(JdbcTransactionException.Category.TIMEOUT,failure.category());assertEquals("55P03",sqlCause(failure).getSQLState());
                assertTrue(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(4));assertEquals(0,handlers.get());
                TransactionNotificationContract.emptyCommands(schema.dataSource());holder.rollback();
                tx(engine).inWriteTransaction(()->{
                    Connection bound=engine.connectionFactory().currentTransactionConnection();
                    long before=scalar(bound,"select setting::bigint from pg_settings where name='lock_timeout'");
                    engine.start(command("blocked"));assertEquals(before,scalar(bound,"select setting::bigint from pg_settings where name='lock_timeout'"));return null;
                });assertEquals(1,handlers.get());
            }
        }
    }

    @TestFactory Stream<DynamicTest> reconnectOriginalKeyResolvesUncertainCommit(){
        return Stream.of(false,true).map(committed->DynamicTest.dynamicTest("serverCommitted="+committed,()->{
            try(var schema=database.createSchema()) {
                var faults=new TransactionTestDataSource(schema.dataSource());AtomicInteger handlers=new AtomicInteger();
                try(var first=engine(faults,handlers::incrementAndGet)) {
                    faults.commitBeforeFailure=committed;faults.commitFailure=new SQLException("acknowledgment lost","08006");
                    var failure=assertThrows(JdbcTransactionException.class,()->first.start(command("same")));
                    assertTrue(JdbcTransactionException.requiresReconciliation(failure));
                }
                faults.commitFailure=null;assertEquals(committed?1:0,count(schema.dataSource(),"workflow_instance"));
                try(var reconnected=engine(schema.dataSource(),handlers::incrementAndGet)) {
                    var replay=reconnected.start(command("same"));assertEquals(committed,replay.idempotentRepeat());
                    assertEquals(committed?1:2,handlers.get());assertEquals(1,count(schema.dataSource(),"workflow_instance"));
                    assertEquals(replay.asIdempotentRepeat(),reconnected.start(command("same")));assertSequences(schema.dataSource());
                }
            }
        }));
    }

    @Test void replayPreservesOriginalSnapshotAndCanonicalBinarySignalAfterRestart()throws Exception {
        try(var schema=database.createSchema()) {
            StartWorkflowResult started;WorkflowCommandResult signalled;
            try(var first=engine(schema.dataSource(),()->{})) {
                started=first.start(command("same"));signalled=first.signal(signal(started.workflowInstanceId(),"signal",new EventMessage(new byte[]{0,1,2},"application/octet-stream",null,null,false,Map.of())));
            }
            try(var second=engine(schema.dataSource(),()->{fail("replay ran handler");})) {
                assertEquals(started.asIdempotentRepeat(),second.start(command("same")));
                assertEquals(signalled.asIdempotentRepeat(),second.signal(signal(started.workflowInstanceId(),"signal",new EventMessage(new byte[]{0,1,2},"application/octet-stream",null,null,false,Map.of()))));
                assertThrows(WorkflowIdempotencyConflictException.class,()->second.signal(signal(started.workflowInstanceId(),"signal",new EventMessage(new byte[]{0,1,3},"application/octet-stream",null,null,false,Map.of()))));
            }
        }
    }

    @TestFactory Stream<DynamicTest> uncertainSignalCommitRequiresCallerReplayAndCanRepeatExternalEffects(){
        return Stream.of(false,true).map(committed->DynamicTest.dynamicTest("signalServerCommitted="+committed,()->{
            try(var schema=database.createSchema()) {
                var faults=new TransactionTestDataSource(schema.dataSource());AtomicInteger effects=new AtomicInteger();WorkflowInstanceId id;
                try(var first=engine(faults,()->{},effects::incrementAndGet,Map.of())) {
                    id=first.start(command("same")).workflowInstanceId();
                    faults.commitBeforeFailure=committed;faults.commitFailure=new SQLException("lost acknowledgment","08006");
                    assertThrows(JdbcTransactionException.class,()->first.signal(signal(id,"signal",EventMessage.empty())));
                    assertEquals(1,effects.get(),"no automatic handler replay");
                }
                faults.commitFailure=null;
                try(var second=engine(schema.dataSource(),()->{},effects::incrementAndGet,Map.of())) {
                    var result=second.signal(signal(id,"signal",EventMessage.empty()));assertEquals(committed,result.idempotentRepeat());
                    assertEquals(committed?1:2,effects.get(),"external effects survive database rollback and may repeat on caller retry");
                    assertEquals(result.asIdempotentRepeat(),second.signal(signal(id,"signal",EventMessage.empty())));
                    assertEquals(5,count(schema.dataSource(),"workflow_event"));assertEquals(2,count(schema.dataSource(),"workflow_command_result"));assertSequences(schema.dataSource());
                }
            }
        }));
    }

    @Test void exactRouteReplaysAfterCompletionAndRejectsChangedPayload()throws Exception {
        try(var schema=database.createSchema();var engine=engine(schema.dataSource(),()->{})) {
            var started=engine.start(command("same"));
            var event=new WorkflowEvent(new EventMetadata(UUID.randomUUID(),new EventName("order.approved"),"test","corr",null,null,started.workflowInstanceId(),"same",null,"1",NOW,NOW,Map.of()),EventMessage.json(Map.of("v",1)));
            var route=WorkflowEventRoute.exact(started.workflowInstanceId(),event.eventName());
            assertEquals(WorkflowRoutingOutcome.ROUTED,engine.route(event,route).outcome());int events=count(schema.dataSource(),"workflow_event");
            assertEquals(WorkflowRoutingOutcome.ROUTED,engine.route(event,route).outcome());assertEquals(events,count(schema.dataSource(),"workflow_event"));
            assertThrows(WorkflowIdempotencyConflictException.class,()->engine.route(new WorkflowEvent(event.metadata(),EventMessage.json(Map.of("v",2))),route));
        }
    }

    @TestFactory Stream<DynamicTest> sameKeySignalAndRouteWaitForOuterCommit(){
        return Stream.of(false,true).map(routed->DynamicTest.dynamicTest("routed="+routed,()->{
            try(var schema=database.createSchema()) {
                var observed=new DuplicatesPostgresIT.ObservedSource(schema.dataSource());AtomicInteger handlers=new AtomicInteger();
                try(var first=engine(schema.dataSource(),()->{},handlers::incrementAndGet,Map.of());var second=engine(observed.source,()->{},handlers::incrementAndGet,Map.of())) {
                    var started=first.start(command("same"));
                    var event=new WorkflowEvent(new EventMetadata(UUID.randomUUID(),new EventName("order.approved"),"test","corr",null,null,started.workflowInstanceId(),"same",null,"1",NOW,NOW,Map.of()),EventMessage.empty());
                    java.util.function.Function<JdbcWorkflowEngine,Object> operation=engine->routed?engine.route(event,WorkflowEventRoute.exact(started.workflowInstanceId(),event.eventName())):engine.signal(signal(started.workflowInstanceId(),"signal",EventMessage.empty()));
                    var held=new CountDownLatch(1);var release=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
                    try {
                        var a=executor.submit(()->tx(first).inWriteTransaction(()->{Object result=operation.apply(first);held.countDown();await(release);return result;}));await(held);
                        var b=executor.submit(()->operation.apply(second));awaitBlocked(schema.dataSource(),observed.application);assertEquals(1,handlers.get());
                        release.countDown();Object original=a.get(15,TimeUnit.SECONDS),repeat=b.get(15,TimeUnit.SECONDS);
                        if(routed){assertEquals(WorkflowRoutingOutcome.ROUTED,((WorkflowRoutingResult)original).outcome());assertEquals(WorkflowRoutingOutcome.ROUTED,((WorkflowRoutingResult)repeat).outcome());}
                        else assertEquals(((WorkflowCommandResult)original).asIdempotentRepeat(),repeat);
                        assertEquals(1,handlers.get());assertEquals(2,count(schema.dataSource(),"workflow_command_result"));assertSequences(schema.dataSource());
                    }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
                }
            }
        }));
    }

    @Test void directAppendAndEngineShareCounterAndOverflowRollsBack()throws Exception {
        try(var schema=database.createSchema();var engine=engine(schema.dataSource(),()->{})) {
            var started=engine.start(command("same"));var first=ports(schema.dataSource(),false);
            var tracked=new DuplicatesPostgresIT.ObservedSource(schema.dataSource());var direct=ports(tracked.source,false);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
            try {
                var a=executor.submit(()->tx(engine).inWriteTransaction(()->{engine.cancel(new CancelWorkflowCommand(started.workflowInstanceId(),command("cancel").metadata()));held.countDown();await(release);return null;}));await(held);
                var b=executor.submit(()->direct.events().append(StorageValueContract.event(started.workflowInstanceId(),EventMessage.empty(),NOW)));
                awaitBlocked(schema.dataSource(),tracked.application);release.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);assertSequences(schema.dataSource());
                int before=count(schema.dataSource(),"workflow_event");
                try(Connection c=schema.openConnection();Statement s=c.createStatement()){s.executeUpdate("update workflow_event_sequence set last_sequence=9223372036854775807");}
                var failure=assertThrows(WorkflowInfrastructureException.class,()->first.events().append(StorageValueContract.event(started.workflowInstanceId(),EventMessage.empty(),NOW)));
                assertEquals("22003",sqlCause(failure).getSQLState());assertEquals(before,count(schema.dataSource(),"workflow_event"));
                try(Connection c=schema.openConnection()){assertEquals(Long.MAX_VALUE,scalar(c,"select last_sequence from workflow_event_sequence"));}
            }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }

    @Test void directAppendsSerializePerInstanceAndRollbackCounterWithInsert()throws Exception {
        try(var schema=database.createSchema()) {
            var first=ports(schema.dataSource(),true);var tracked=new DuplicatesPostgresIT.ObservedSource(schema.dataSource());var second=ports(tracked.source,false);
            var id=WorkflowInstanceId.random();var other=WorkflowInstanceId.random();var event=StorageValueContract.event(id,EventMessage.empty(),NOW);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);ExecutorService executor=Executors.newFixedThreadPool(2);
            try {
                var a=executor.submit(()->first.jdbcTransactions().inWriteTransaction(()->{first.events().append(event);held.countDown();await(release);return null;}));await(held);
                var b=executor.submit(()->second.events().append(StorageValueContract.event(id,EventMessage.empty(),NOW)));
                awaitBlocked(schema.dataSource(),tracked.application);
                ports(schema.dataSource(),false).events().append(StorageValueContract.event(other,EventMessage.empty(),NOW));
                assertEquals(1,count(schema.dataSource(),"workflow_event"),"another instance makes progress while first counter is locked");
                release.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
                assertThrows(WorkflowInfrastructureException.class,()->second.events().append(event));
                second.events().append(StorageValueContract.event(id,EventMessage.empty(),NOW));
                assertEquals(4,count(schema.dataSource(),"workflow_event"));assertSequences(schema.dataSource());
                assertThrows(Abort.class,()->first.jdbcTransactions().inWriteTransaction(()->{first.events().append(StorageValueContract.event(id,EventMessage.empty(),NOW));throw new Abort();}));
                assertEquals(4,count(schema.dataSource(),"workflow_event"));assertSequences(schema.dataSource());
            }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }

    @Test void schemaAndCompleteKeyAreLockIdentityAndCollisionsOnlySerialize()throws Exception {
        assertNotEquals(PostgresqlDatabaseStrategy.commandLockIdentity("a","bc"),PostgresqlDatabaseStrategy.commandLockIdentity("ab","c"));
        assertNotEquals(PostgresqlDatabaseStrategy.commandLockIdentity("s","a\u0000b"),PostgresqlDatabaseStrategy.commandLockIdentity("s","a%00b"));
        try(var left=database.createSchema();var right=database.createSchema();var a=engine(left.dataSource(),()->{});var b=engine(right.dataSource(),()->{})) {
            var observed=new DuplicatesPostgresIT.ObservedSource(left.dataSource());
            try(var contender=engine(observed.source,()->{})) {
                var held=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
                try {
                    var holder=executor.submit(()->tx(a).inWriteTransaction(()->{
                        a.start(command("same"));b.start(command("same"));assertEquals(1,count(right.dataSource(),"workflow_instance"));
                        // Force overlapping advisory identity while writing another full key, simulating hash contention.
                        try{a.connectionFactory().strategy().lockCommand(a.connectionFactory().currentTransactionConnection(),"other");}
                        catch(SQLException failure){throw new AssertionError(failure);}
                        held.countDown();await(release);return null;
                    }));await(held);
                    var waiter=executor.submit(()->contender.start(command("other")));awaitBlocked(left.dataSource(),observed.application);
                    release.countDown();holder.get(15,TimeUnit.SECONDS);assertFalse(waiter.get(15,TimeUnit.SECONDS).idempotentRepeat());
                    assertEquals(2,count(left.dataSource(),"workflow_instance"));assertEquals(2,count(left.dataSource(),"workflow_command_result"));
                }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
            }
        }
    }

    static JdbcWorkflowPersistence ports(DataSource source,boolean initialize){return JdbcWorkflowPersistence.create(null,null,null,null,source,initialize,Map.of());}
    static void assertSequences(DataSource source)throws SQLException {
        try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("select e.workflow_instance_id,count(*),count(distinct sequence_number),min(sequence_number),max(sequence_number),s.last_sequence from workflow_event e join workflow_event_sequence s on s.workflow_instance_id=e.workflow_instance_id group by e.workflow_instance_id,s.last_sequence")) {
            int groups=0;while(r.next()){groups++;long count=r.getLong(2);assertEquals(count,r.getLong(3));assertEquals(1,r.getLong(4));assertEquals(count,r.getLong(5));assertEquals(count,r.getLong(6));}assertTrue(groups>0);
        }
    }
    static void awaitBlocked(DataSource source,String application)throws SQLException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        try(Connection c=source.getConnection();PreparedStatement s=c.prepareStatement("select pid,(pg_blocking_pids(pid))[1] from pg_stat_activity where application_name=? and wait_event_type='Lock'")) {
            s.setString(1,application);do{try(ResultSet r=s.executeQuery()){if(r.next()){assertNotEquals(r.getInt(1),r.getInt(2));assertTrue(r.getInt(2)>0);return;}}LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}while(System.nanoTime()<deadline);
        }fail("No independent backend lock wait observed");
    }
    static void await(CountDownLatch latch){try{assertTrue(latch.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void barrier(CyclicBarrier barrier){try{barrier.await(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    static Object outcome(Callable<?> work){try{return work.call();}catch(Exception failure){return failure;}}
    static SQLException sqlCause(Throwable failure){for(Throwable f=failure;f!=null;f=f.getCause())if(f instanceof SQLException s)return s;throw new AssertionError(failure);}
    static long scalar(Connection c,String sql){try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}catch(SQLException failure){throw new AssertionError(failure);}}
    static final class Abort extends RuntimeException { }
}
