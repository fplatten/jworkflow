package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.outbox.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import org.jworkflow.application.RetryBackoffPolicy;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.LeaseContract.*;
import static org.jworkflow.jdbc.TransactionNotificationContract.count;

@Timeout(90)
class LeasesPostgresIT {
    static PostgresTestDatabase database;
    @BeforeAll static void start(){database=PostgresTestDatabase.start();}
    @AfterAll static void stop()throws Exception{if(database!=null)database.close();}
    static JdbcWorkflowPersistence ports(DataSource source,boolean initialize){return JdbcWorkflowPersistence.create(null,null,null,null,source,initialize,Map.of());}
    @TestFactory Stream<DynamicTest> leaseSqlBindsUntrustedValues() {
        return Arrays.stream(Kind.values()).map(kind -> DynamicTest.dynamicTest(kind.name(), () -> {
            try (var schema = database.createSchema()) {
                var source = schema.dataSource();
                LeaseSqlSafetyContract.assertBoundValues(ports(source, true), source, kind);
            }
        }));
    }
    @TestFactory Stream<DynamicTest> expiryAndGenerationContracts(){return Arrays.stream(Kind.values()).map(kind->DynamicTest.dynamicTest(kind.name(),()->{
        try(var schema=database.createSchema()){
            var p=ports(schema.dataSource(),true);expiryAndFencing(p,kind,true);
            assertEquals(0,count(schema.dataSource(),"event_status"));assertEquals(0,count(schema.dataSource(),"workflow_event"));
        }
    }));}

    @TestFactory Stream<DynamicTest> disjointOrderedBatchesSkipLockedRowsAndRollbackReleases(){
        List<DynamicTest> tests=new ArrayList<>();
        for(Kind kind:Kind.values())for(boolean rollback:List.of(false,true))tests.add(DynamicTest.dynamicTest(kind+" rollback="+rollback,()->{
            try(var schema=database.createSchema()){
                var first=ports(schema.dataSource(),true);var second=ports(schema.dataSource(),false);List<UUID> ids=new ArrayList<>();
                for(int i=0;i<6;i++)ids.add(seed(first,kind));ids.sort(Comparator.comparing(UUID::toString));
                var held=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
                AtomicReference<List<Lease>> original=new AtomicReference<>();
                try {
                    var holder=executor.submit(()->{try{first.jdbcTransactions().inWriteTransaction(()->{
                        original.set(claim(first,kind,NOW,"worker",NOW.plusSeconds(10),2));assertEquals(ids.subList(0,2),original.get().stream().map(Lease::id).toList());
                        held.countDown();await(release);if(rollback)throw new Abort();return null;
                    });}catch(Abort expected){}});await(held);
                    var waiter=executor.submit(()->acquire(second,kind,NOW,"other",NOW.plusSeconds(10),2));
                    var later=waiter.get(3,TimeUnit.SECONDS);assertEquals(ids.subList(2,4),later.stream().map(Lease::id).toList());
                    assertTrue(Collections.disjoint(original.get().stream().map(Lease::token).toList(),later.stream().map(Lease::token).toList()));
                    release.countDown();holder.get(15,TimeUnit.SECONDS);
                    var remainder=acquire(second,kind,NOW,"worker",NOW.plusSeconds(10),6);
                    assertEquals(rollback?List.of(ids.get(0),ids.get(1),ids.get(4),ids.get(5)):ids.subList(4,6),remainder.stream().map(Lease::id).toList());
                    assertTrue(remainder.stream().noneMatch(r->original.get().stream().anyMatch(o->o.token().equals(r.token()))));
                    assertEquals(6,count(schema.dataSource(),kind.table()+" where status_value='CLAIMED' and claim_token is not null"));
                    assertTrue(acquire(second,kind,NOW,"third",NOW.plusSeconds(10),2).isEmpty());
                }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
            }
        }));return tests.stream();
    }

    @TestFactory Stream<DynamicTest> restartRetainsValidClaimsAndRecoversExpiredGenerations(){return Arrays.stream(Kind.values()).map(kind->DynamicTest.dynamicTest(kind.name(),()->{
        try(var schema=database.createSchema()){
            var p=ports(schema.dataSource(),true);seed(p,kind);var original=acquire(p,kind,NOW,"worker",NOW.plusSeconds(10),1).get(0);
            try(var engine=bareEngine(schema.dataSource(),Clock.fixed(NOW.plusSeconds(9),ZoneOffset.UTC))){
                var restarted=JdbcWorkflowPersistence.from(engine.connectionFactory());assertTrue(acquire(restarted,kind,NOW.plusSeconds(9),"worker",NOW.plusSeconds(20),1).isEmpty());
            }
            try(var engine=bareEngine(schema.dataSource(),Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC))){
                var restarted=JdbcWorkflowPersistence.from(engine.connectionFactory());var newLease=acquire(restarted,kind,NOW.plusSeconds(10),"worker",NOW.plusSeconds(20),1).get(0);
                assertEquals(original.id(),newLease.id());assertNotEquals(original.token(),newLease.token());
                var completionTime = NOW.plusSeconds(10);
                assertThrows(StaleWorkflowClaimException.class,()->finish(restarted,kind,original,0,completionTime,false));
            }
        }
    }));}

    @Test void staleInboxCannotDispatchAndFinalGuardRollsBackAllWorkflowWrites()throws Exception {
        try(var schema=database.createSchema();var engine=TransactionNotificationContract.engine(WorkflowEngine.Type.POSTGRESQL,schema.dataSource(),event->{})){
            var p=JdbcWorkflowPersistence.from(engine.connectionFactory());var tx=(JdbcTransactionManager)engine.transactionManager();
            seed(p,Kind.INBOX);Lease old=acquire(p,Kind.INBOX,NOW,"worker",NOW.plusSeconds(1),1).get(0);
            Lease current=acquire(p,Kind.INBOX,NOW.plusSeconds(1),"worker",NOW.plusSeconds(2),1).get(0);AtomicInteger dispatches=new AtomicInteger();
            var processor=new InboxProcessingService(p.inbox(),p.eventStatuses(),tx,message->{dispatches.incrementAndGet();return List.of(TransactionNotificationContract.command("stale-inbox"));},
                    command->engine.start((StartWorkflowCommand)command),Clock.fixed(NOW,ZoneOffset.UTC));
            var staleInbox = (InboxMessage)old.value();
            assertThrows(StaleWorkflowClaimException.class,()->processor.process(staleInbox,"worker"));assertEquals(0,dispatches.get());
            assertThrows(WorkflowPersistenceException.class,()->tx.inWriteTransaction(()->{
                engine.start(TransactionNotificationContract.command("must-roll-back"));history(p,Kind.INBOX,old);
                assertThrows(StaleWorkflowClaimException.class,()->finish(p,Kind.INBOX,old,0,NOW,false));return null;
            }));
            TransactionNotificationContract.emptyCommands(schema.dataSource());assertEquals(0,count(schema.dataSource(),"workflow_event_sequence"));assertEquals(0,count(schema.dataSource(),"workflow_inbox_attempt"));assertEquals(0,count(schema.dataSource(),"event_status"));
            assertEquals(current.token(),p.inbox().findById(old.id()).orElseThrow().claimToken());
            processor.process((InboxMessage)current.value(),"worker");assertEquals(1,dispatches.get());assertEquals(1,count(schema.dataSource(),"workflow_instance"));
        }
    }

    @Test void inboxProcessingHoldsGenerationAndSkipsReclaimEvenAfterExpiry()throws Exception {
        try(var schema=database.createSchema();var engine=TransactionNotificationContract.engine(WorkflowEngine.Type.POSTGRESQL,schema.dataSource(),event->{})){
            var p=JdbcWorkflowPersistence.from(engine.connectionFactory());var other=ports(schema.dataSource(),false);seed(p,Kind.INBOX);
            var lease=acquire(p,Kind.INBOX,NOW,"worker",NOW.plusSeconds(1),1).get(0);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
            var processor=new InboxProcessingService(p.inbox(),p.eventStatuses(),engine.transactionManager(),message->{held.countDown();await(release);return List.of(TransactionNotificationContract.command("held-inbox"));},
                    command->engine.start((StartWorkflowCommand)command),Clock.fixed(NOW.plusSeconds(2),ZoneOffset.UTC));
            try {
                var processing=executor.submit(()->processor.process((InboxMessage)lease.value(),"worker"));await(held);
                var reclaimed=executor.submit(()->{
                    assertEquals(0,release(other,Kind.INBOX,NOW.plusSeconds(2)));
                    return acquire(other,Kind.INBOX,NOW.plusSeconds(2),"same-worker",NOW.plusSeconds(3),1);
                });assertTrue(reclaimed.get(3,TimeUnit.SECONDS).isEmpty());release.countDown();processing.get(15,TimeUnit.SECONDS);
                assertEquals(InboxMessageStatus.PROCESSED,p.inbox().findById(lease.id()).orElseThrow().status());assertEquals(1,count(schema.dataSource(),"workflow_instance"));
            }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }

    @Test void staleTimerCannotCommitWorkflowChangesAndCurrentGenerationFiresOnce()throws Exception {
        try(var schema=database.createSchema()){
            MutableClock clock=new MutableClock(NOW);
            try(var engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(schema.dataSource()).initialize(true).timerPolling(false).clock(clock).definition(TransactionNotificationContract.definition()).build()){
                var p=JdbcWorkflowPersistence.from(engine.connectionFactory());var started=engine.start(TransactionNotificationContract.command("timer"));
                var worker=JdbcWorkflowEngine.class.getDeclaredField("workerId");worker.setAccessible(true);String owner=(String)worker.get(engine);
                Instant due=NOW.plusSeconds(3600);clock.now.set(due.plusSeconds(1));
                Lease old=acquire(p,Kind.TIMER,due,owner,due.plusSeconds(1),1).get(0);Lease current=acquire(p,Kind.TIMER,due.plusSeconds(1),owner,due.plusSeconds(2),1).get(0);
                var method=JdbcWorkflowEngine.class.getDeclaredMethod("processClaimedTimer",WorkflowTimer.class);method.setAccessible(true);
                int events=count(schema.dataSource(),"workflow_event"),outbox=count(schema.dataSource(),"workflow_outbox");
                var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(engine,(WorkflowTimer)old.value()));assertInstanceOf(StaleWorkflowClaimException.class,failure.getCause());
                assertEquals(started.snapshot(),engine.snapshot(started.workflowInstanceId()));assertEquals(events,count(schema.dataSource(),"workflow_event"));assertEquals(outbox,count(schema.dataSource(),"workflow_outbox"));assertEquals(0,count(schema.dataSource(),"workflow_timer_attempt"));
                // A stale final timer guard after successful workflow writes must also poison the whole transaction.
                var transactionManager = engine.transactionManager();
                assertThrows(WorkflowPersistenceException.class,()->transactionManager.inTransaction(()->{
                    engine.start(TransactionNotificationContract.command("stale-timer-effects"));history(p,Kind.TIMER,old);
                    var completionTime = clock.instant();
                    assertThrows(StaleWorkflowClaimException.class,()->finish(p,Kind.TIMER,old,0,completionTime,false));return null;
                }));assertEquals(1,count(schema.dataSource(),"workflow_instance"));assertEquals(1,count(schema.dataSource(),"workflow_command_result"));assertEquals(events,count(schema.dataSource(),"workflow_event"));
                method.invoke(engine,(WorkflowTimer)current.value());assertEquals(WorkflowStatus.COMPLETED,engine.snapshot(started.workflowInstanceId()).status());
                assertEquals(1,count(schema.dataSource(),"workflow_timer_attempt"));assertEquals(WorkflowTimerStatus.FIRED,p.timers().findByWorkflowInstance(started.workflowInstanceId()).get(0).status());
                var timerRepository = p.timers();
                var staleTimer = (WorkflowTimer)old.value();
                assertThrows(StaleWorkflowClaimException.class,()->timerRepository.save(staleTimer));
            }
        }
    }

    @Test void workflowCancellationInvalidatesClaimedTimer()throws Exception {
        try(var schema=database.createSchema();var engine=TransactionNotificationContract.engine(WorkflowEngine.Type.POSTGRESQL,schema.dataSource(),event->{})){
            var p=JdbcWorkflowPersistence.from(engine.connectionFactory());var started=engine.start(TransactionNotificationContract.command("cancel"));var timer=p.timers().findByWorkflowInstance(started.workflowInstanceId()).get(0);
            var lease=acquire(p,Kind.TIMER,timer.dueAt(),"worker",timer.dueAt().plusSeconds(1),1).get(0);
            engine.cancel(new CancelWorkflowCommand(started.workflowInstanceId(),TransactionNotificationContract.command("cancel-key").metadata()));
            var dueAt = timer.dueAt();
            assertThrows(StaleWorkflowClaimException.class,()->finish(p,Kind.TIMER,lease,0,dueAt,false));
            assertEquals(WorkflowTimerStatus.CANCELED,p.timers().findByWorkflowInstance(started.workflowInstanceId()).get(0).status());
        }
    }

    @TestFactory Stream<DynamicTest> stalePublishersCannotRecordSuccessOrFailureAfterSend(){return Stream.of(false,true).map(failSend->DynamicTest.dynamicTest("publisherThrows="+failSend,()->{
        try(var schema=database.createSchema()){
            var p=ports(schema.dataSource(),true);var other=ports(schema.dataSource(),false);seed(p,Kind.OUTBOX);var old=acquire(p,Kind.OUTBOX,NOW,"worker",NOW.plusSeconds(1),1).get(0);
            AtomicInteger sends=new AtomicInteger();AtomicReference<Lease> current=new AtomicReference<>();
            var service=publisher(p,message->{assertFalse(p.transactions().isTransactionActive());sends.incrementAndGet();
                current.set(acquire(other,Kind.OUTBOX,NOW.plusSeconds(1),"worker",NOW.plusSeconds(2),1).get(0));if(failSend)throw new IllegalStateException("transport failed after send");},Clock.fixed(NOW.plusSeconds(1),ZoneOffset.UTC));
            var staleOutbox = (OutboxMessage)old.value();
            assertThrows(StaleWorkflowClaimException.class,()->service.publish(staleOutbox,"worker"));
            assertEquals(1,sends.get());assertEquals(0,count(schema.dataSource(),"workflow_outbox_attempt"));assertEquals(0,count(schema.dataSource(),"event_status"));
            assertEquals(current.get().token(),p.outbox().findById(old.id()).orElseThrow().claimToken());
            publisher(p,message->sends.incrementAndGet(),Clock.fixed(NOW.plusSeconds(2),ZoneOffset.UTC)).publish((OutboxMessage)current.get().value(),"worker");
            assertEquals(2,sends.get());assertEquals(1,count(schema.dataSource(),"workflow_outbox_attempt"));assertEquals(1,count(schema.dataSource(),"event_status"));
            assertThrows(StaleWorkflowClaimException.class,()->service.publish(staleOutbox,"worker"));assertEquals(2,sends.get());
        }
    }));}

    @TestFactory Stream<DynamicTest> sendBeforeCommitFailureCanRedeliverWithoutCorruptingHistory(){return Stream.of(false,true).map(committed->DynamicTest.dynamicTest("recordCommitted="+committed,()->{
        try(var schema=database.createSchema()){
            var faults=new TransactionTestDataSource(schema.dataSource());var p=ports(faults,true);seed(p,Kind.OUTBOX);var old=acquire(p,Kind.OUTBOX,NOW,"worker",NOW.plusSeconds(1),1).get(0);AtomicInteger sends=new AtomicInteger();
            var service=publisher(p,message->{assertFalse(p.transactions().isTransactionActive());sends.incrementAndGet();faults.commitBeforeFailure=committed;faults.commitFailure=new SQLException("lost record acknowledgment","08006");},Clock.fixed(NOW,ZoneOffset.UTC));
            var staleOutbox = (OutboxMessage)old.value();
            assertThrows(JdbcTransactionException.class,()->service.publish(staleOutbox,"worker"));faults.commitFailure=null;
            assertEquals(committed?1:0,count(schema.dataSource(),"workflow_outbox_attempt"));assertEquals(committed?1:0,count(schema.dataSource(),"event_status"));
            var recovered=acquire(ports(schema.dataSource(),false),Kind.OUTBOX,NOW.plusSeconds(1),"worker",NOW.plusSeconds(2),1);
            if(committed)assertTrue(recovered.isEmpty());else{
                assertEquals(1,recovered.size());assertNotEquals(old.token(),recovered.get(0).token());publisher(p,message->sends.incrementAndGet(),Clock.fixed(NOW,ZoneOffset.UTC)).publish((OutboxMessage)recovered.get(0).value(),"worker");
            }
            assertEquals(committed?1:2,sends.get());assertEquals(1,count(schema.dataSource(),"workflow_outbox_attempt"));assertEquals(1,count(schema.dataSource(),"event_status"));
        }
    }));}

    @Test void outboxRetriesDeadLettersAndRejectsOuterTransactions()throws Exception {
        try(var schema=database.createSchema()){
            var p=ports(schema.dataSource(),true);seed(p,Kind.OUTBOX);var first=acquire(p,Kind.OUTBOX,NOW,"worker",NOW.plusSeconds(1),1).get(0);AtomicInteger sends=new AtomicInteger();MutableClock clock=new MutableClock(NOW);
            var service=publisher(p,message->{assertFalse(p.transactions().isTransactionActive());sends.incrementAndGet();throw new IllegalStateException("failed");},clock);
            var transactionManager = p.jdbcTransactions();
            assertThrows(IllegalStateException.class,()->transactionManager.inTransaction(()->service.publish((OutboxMessage)first.value(),"worker")));assertEquals(0,sends.get());
            assertEquals(OutboxMessageStatus.RETRY_SCHEDULED,service.publish((OutboxMessage)first.value(),"worker").status());
            assertTrue(acquire(p,Kind.OUTBOX,NOW.plusSeconds(1).minusNanos(1),"worker",NOW.plusSeconds(2),1).isEmpty());clock.now.set(NOW.plusSeconds(1));
            var next=acquire(p,Kind.OUTBOX,clock.instant(),"worker",NOW.plusSeconds(2),1).get(0);assertNotEquals(first.token(),next.token());
            assertEquals(OutboxMessageStatus.DEAD_LETTER,service.publish((OutboxMessage)next.value(),"worker").status());assertEquals(2,sends.get());
            assertEquals(2,count(schema.dataSource(),"workflow_outbox_attempt"));assertEquals(2,count(schema.dataSource(),"event_status"));assertTrue(acquire(p,Kind.OUTBOX,NOW.plusSeconds(10),"worker",NOW.plusSeconds(20),1).isEmpty());
        }
    }
    static OutboxPublisherService publisher(JdbcWorkflowPersistence p,DestinationPublisher send,Clock clock){return new OutboxPublisherService(p.outbox(),p.eventStatuses(),p.transactions(),send,new RetryBackoffPolicy(){
        public boolean exhausted(int attempt){return attempt>=2;}public Instant nextAttemptAt(int attempt,Instant now){return now.plusSeconds(1);}
    },clock);}
    static JdbcWorkflowEngine bareEngine(DataSource source,Clock clock)throws Exception{return (JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(source).initialize(false).timerPolling(false).clock(clock).build();}
    static void await(CountDownLatch latch){try{assertTrue(latch.await(15,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
    static final class Abort extends RuntimeException { }
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;MutableClock(Instant instant){now=new AtomicReference<>(instant);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}
    }
}
