package org.jworkflow.jdbc;

import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.outbox.*;
import org.jworkflow.persistence.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** PG-05 storage/query assertions, deliberately independent of unfinished concurrent workers. */
@Timeout(120)
class ValuesPostgresIT {
    private static PostgresTestDatabase database;
    @BeforeAll static void start() { database=PostgresTestDatabase.start(); }
    @AfterAll static void stop() throws Exception { if(database!=null)database.close(); }

    @Test void nullEmptyLargeBinaryUnicodeJsonAndRedactionRoundTrip() throws Exception {
        try(var schema=database.createSchema()) { StorageValueContract.payloads(persistence(schema)); }
    }

    @Test void allRepositoryTimeValuesAndExactRevisionsRoundTrip() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            StorageValueContract.exactTimesAndRevisions(persistence);
            try(Connection connection=schema.openConnection(); PreparedStatement statement=connection.prepareStatement("select created_at from workflow_instance where created_at=?")) {
                BigDecimal min=PostgresqlInstantCodec.encode(Instant.MIN);
                statement.setBigDecimal(1,min);
                try(ResultSet rows=statement.executeQuery()) { assertTrue(rows.next()); assertEquals(min,rows.getBigDecimal(1)); }
            }
        }
    }

    @Test void chronologicalBoundedQueriesAndKeysetPagesHandleNanosecondsAndTies() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            List<WorkflowSnapshot> expected=new ArrayList<>();
            for(Instant instant:StorageValueContract.TIMES) for(int tie=0;tie<3;tie++) {
                var snapshot=StorageValueContract.snapshot(WorkflowInstanceId.random(),instant);
                persistence.instances().insert(snapshot); expected.add(snapshot);
            }
            expected.sort(Comparator.comparing(WorkflowSnapshot::updatedAt).thenComparing(s->s.instanceId().toString()));
            List<WorkflowSnapshot> actual=new ArrayList<>(); ActiveWorkflowCursor cursor=null;
            for(int page=0;page<=expected.size();page++) {
                var rows=persistence.instances().findActiveAfter(cursor,2);
                assertTrue(rows.size()<=2);
                if(rows.isEmpty())break;
                actual.addAll(rows); var last=rows.get(rows.size()-1);
                cursor=new ActiveWorkflowCursor(last.updatedAt(),last.instanceId());
            }
            assertEquals(expected,actual); assertEquals(expected.size(),new HashSet<>(actual).size());
            assertEquals(expected.subList(2,5),persistence.instances().findAll(3,2));
            assertEquals(expected.subList(0,2),persistence.instances().findActiveByCorrelation("orders","corr",2));
            assertEquals(expected.subList(0,2),persistence.instances().findActiveByBusinessKey("orders","business",2));
            assertEquals(expected.get(expected.size()-1),persistence.instances().findByBusinessKey("orders","business").orElseThrow());
            assertEquals(expected.stream().filter(s->!s.updatedAt().isAfter(Instant.EPOCH)).toList(),persistence.instances().findStuck(Instant.EPOCH,100));
            assertEquals(expected.subList(0,3),persistence.instances().findByStatus(WorkflowStatus.WAITING,3));
            try(Connection connection=schema.openConnection(); Statement statement=connection.createStatement()) {
                assertEquals(0,MigrationsPostgresIT.scalar(connection,"select count(*) from workflow_instance where updated_at<>created_at"));
            }
        }
    }

    @Test void timerEligibilityUsesExactInclusiveDeadlinesAndCoalescedRetryTimes() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            Instant whole=Instant.parse("2026-01-01T00:00:00Z");
            var instance=WorkflowInstanceId.random();
            var past=StorageValueContract.timer(instance,whole.minusNanos(1),null);
            var equal=StorageValueContract.timer(instance,whole,null);
            var future=StorageValueContract.timer(instance,whole.plusNanos(1),null);
            var retry=StorageValueContract.timer(instance,whole.minusSeconds(1),whole.plusNanos(2));
            for(var timer:List.of(future,retry,equal,past))persistence.timers().save(timer);
            assertEquals(List.of(past,equal),persistence.timers().dueTimers(whole));
            assertEquals(List.of(past,equal,future),persistence.timers().dueTimers(whole.plusNanos(1)));
            assertEquals(List.of(past,equal),persistence.timers().findPending(2));
            assertEquals(List.of(past,equal,future,retry),persistence.timers().dueTimers(whole.plusNanos(2)));
        }
    }

    @Test void timerUpsertRetainsIdentityAndCreationWhileReplacingMutableFields() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            var initial=StorageValueContract.timer(WorkflowInstanceId.random(),Instant.EPOCH,null);
            persistence.timers().save(initial);
            var changed=new WorkflowTimer(initial.timerId(),WorkflowInstanceId.random(),"new-step",Instant.EPOCH.plusNanos(1),"target",null,
                    WorkflowTimerStatus.RETRY_SCHEDULED,2,Instant.EPOCH.plusNanos(3),"worker",Instant.EPOCH.plusNanos(4),Instant.MAX,Instant.EPOCH.plusNanos(5));
            persistence.timers().save(changed);
            var stored=persistence.timers().findByWorkflowInstance(initial.workflowInstanceId()).get(0);
            assertEquals(initial.workflowInstanceId(),stored.workflowInstanceId()); assertEquals(initial.createdAt(),stored.createdAt());
            assertEquals(changed.dueAt(),stored.dueAt()); assertEquals(changed.nextAttemptAt(),stored.nextAttemptAt());
            assertEquals(changed.claimUntil(),stored.claimUntil()); assertEquals(changed.updatedAt(),stored.updatedAt());
            assertEquals(changed.stepName(),stored.stepName()); assertEquals(2,stored.attemptCount());
            assertTrue(persistence.timers().findByWorkflowInstance(changed.workflowInstanceId()).isEmpty());
            try(Connection connection=schema.openConnection()) { assertEquals(1,MigrationsPostgresIT.scalar(connection,"select count(*) from workflow_timer")); }
        }
    }

    @Test void bigintSequencesAndNullOrderingAndTimeStreamBoundsAreDeterministic() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            var instance=WorkflowInstanceId.random();
            Instant time=Instant.EPOCH.plusNanos(1);
            var first=StorageValueContract.event(instance,EventMessage.empty(),time);
            var legacy=StorageValueContract.event(instance,EventMessage.empty(),time);
            persistence.events().append(first); persistence.events().append(legacy);
            try(Connection connection=schema.openConnection(); PreparedStatement statement=connection.prepareStatement("update workflow_event set sequence_number=? where id=?")) {
                statement.setLong(1,4_294_967_296L); statement.setString(2,first.metadata().eventId().toString()); statement.executeUpdate();
                statement.setNull(1,Types.BIGINT); statement.setString(2,legacy.metadata().eventId().toString()); statement.executeUpdate();
            }
            // PG-08 counter is authoritative; manual fixture edits must seed it consistently.
            try(Connection connection=schema.openConnection(); Statement statement=connection.createStatement()) {
                statement.executeUpdate("update workflow_event_sequence set last_sequence=4294967296");
            }
            var next=StorageValueContract.event(instance,EventMessage.empty(),time);
            persistence.events().append(next);
            assertEquals(List.of(legacy,first,next),persistence.events().findByWorkflowInstance(instance));
            try(Connection connection=schema.openConnection()) { assertEquals(4_294_967_297L,MigrationsPostgresIT.scalar(connection,"select max(sequence_number) from workflow_event")); }
            var sorted=List.of(first,legacy,next).stream().sorted(Comparator.comparing(e->e.metadata().eventId().toString())).toList();
            assertEquals(sorted.subList(0,2),persistence.events().findAllAfter(Instant.EPOCH,2));
            assertTrue(persistence.events().findAllAfter(time,10).isEmpty());
            // This API has only a time cursor: no claim of lossless paging through ties.
            WorkflowSnapshot maximum=StorageValueContract.snapshot(WorkflowInstanceId.random(),time).withLockVersion(Long.MAX_VALUE-1);
            persistence.instances().insert(maximum);
            persistence.instances().update(maximum.withLockVersion(Long.MAX_VALUE),Long.MAX_VALUE-1);
            assertEquals(Long.MAX_VALUE,persistence.instances().findById(maximum.instanceId()).orElseThrow().lockVersion());
        }
    }

    @Test void retryCompletionAndExpiryBindingsPreserveNanosecondBoundaries() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            Instant now=Instant.ofEpochSecond(-1,999_999_998), next=now.plusNanos(1);
            // Seed token-bearing states directly to test codecs; PG-09 races have separate coverage.
            InboxMessage inbox=new InboxMessage(null,"external","source",EventMessage.empty(),null,null,now,null,InboxMessageStatus.CLAIMED,0,null,null,"owner",next,"value-token");
            persistence.inbox().insertIfAbsent(inbox);
            assertEquals(0,persistence.inbox().releaseExpiredClaims(now));
            persistence.inbox().scheduleRetry(inbox.messageId(),"owner","value-token",next,"error");
            assertEquals(next,persistence.inbox().findById(inbox.messageId()).orElseThrow().nextAttemptAt());
            persistence.inbox().requestReprocessing(inbox.messageId(),now);
            assertEquals(now,persistence.inbox().findById(inbox.messageId()).orElseThrow().nextAttemptAt());
            claim(schema,"workflow_inbox",inbox.messageId(),next);
            persistence.inbox().markProcessed(inbox.messageId(),"owner","value-token",next);
            assertEquals(next,persistence.inbox().findById(inbox.messageId()).orElseThrow().processedAt());
            claim(schema,"workflow_inbox",inbox.messageId(),next);
            persistence.inbox().markDeadLetter(inbox.messageId(),"owner","value-token","error",next);
            assertTime(schema,"workflow_inbox","dead_lettered_at",inbox.messageId(),next);

            OutboxMessage outbox=StorageValueContract.outbox(EventMessage.empty(),now); persistence.outbox().enqueue(outbox);
            claim(schema,"workflow_outbox",outbox.messageId(),next);
            assertEquals(0,persistence.outbox().releaseExpiredClaims(now));
            persistence.outbox().scheduleRetry(outbox.messageId(),"owner","value-token",next,"error");
            assertEquals(next,persistence.outbox().findById(outbox.messageId()).orElseThrow().nextAttemptAt());
            persistence.outbox().requestRepublishing(outbox.messageId(),now);
            assertEquals(now,persistence.outbox().findPending(1).get(0).nextAttemptAt());
            claim(schema,"workflow_outbox",outbox.messageId(),next);
            persistence.outbox().markPublished(outbox.messageId(),"owner","value-token",next);
            assertEquals(next,persistence.outbox().findById(outbox.messageId()).orElseThrow().publishedAt());
            claim(schema,"workflow_outbox",outbox.messageId(),next);
            persistence.outbox().markDeadLetter(outbox.messageId(),"owner","value-token","error",next);
            assertTime(schema,"workflow_outbox","dead_lettered_at",outbox.messageId(),next);

            var timer=StorageValueContract.timer(WorkflowInstanceId.random(),now,null); persistence.timers().save(timer);
            claim(schema,"workflow_timer",timer.timerId(),next);
            assertEquals(0,persistence.timers().releaseExpiredClaims(now));
            persistence.timers().markFailed(timer.timerId(),"owner","value-token","error",next);
            assertEquals(next,persistence.timers().findByWorkflowInstance(timer.workflowInstanceId()).get(0).nextAttemptAt());
            claim(schema,"workflow_timer",timer.timerId(),next);
            persistence.timers().markFired(timer.timerId(),"owner","value-token",next);
            assertTime(schema,"workflow_timer","updated_at",timer.timerId(),next);
            persistence.timers().save(timer); persistence.timers().cancel(timer.timerId(),next);
            assertTime(schema,"workflow_timer","updated_at",timer.timerId(),next);
            for(String table:List.of("workflow_timer","workflow_inbox","workflow_outbox")) {
                UUID id=table.equals("workflow_timer")?timer.timerId():table.equals("workflow_inbox")?inbox.messageId():outbox.messageId();
                claim(schema,table,id,next);
            }
            assertEquals(1,persistence.timers().releaseExpiredClaims(next));
            assertEquals(1,persistence.inbox().releaseExpiredClaims(next));
            assertEquals(1,persistence.outbox().releaseExpiredClaims(next));
            assertNull(persistence.timers().findByWorkflowInstance(timer.workflowInstanceId()).get(0).claimUntil());
            assertNull(persistence.inbox().findById(inbox.messageId()).orElseThrow().claimUntil());
            assertNull(persistence.outbox().findById(outbox.messageId()).orElseThrow().claimUntil());
        }
    }

    @Test void corruptedOutOfRangeInstantsFailRatherThanLosingPrecision() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema);
            var snapshot=StorageValueContract.snapshot(WorkflowInstanceId.random(),Instant.EPOCH);
            persistence.instances().insert(snapshot);
            try(Connection connection=schema.openConnection(); Statement statement=connection.createStatement()) {
                statement.executeUpdate("update workflow_instance set updated_at=999999999999999999999.000000000");
            }
            assertThrows(PersistenceSerializationException.class,()->persistence.instances().findById(snapshot.instanceId()));
        }
    }

    @Test void pendingOutboxOrderUsesCreationForNullRetryAndStableIdsForTies() throws Exception {
        try(var schema=database.createSchema()) {
            var persistence=persistence(schema); List<OutboxMessage> expected=new ArrayList<>();
            Instant whole=Instant.EPOCH;
            for(int i=0;i<6;i++) {
                Instant created=i<3?whole:whole.plusNanos(1);
                Instant retry=i%2==0?null:whole.plusNanos(2);
                OutboxMessage message=new OutboxMessage(null,UUID.randomUUID(),"destination",UUID.randomUUID().toString(),EventMessage.empty(),
                        null,null,created,null,OutboxMessageStatus.PENDING,0,retry,null,null,null);
                persistence.outbox().enqueue(message);expected.add(message);
            }
            expected.sort(Comparator.comparing((OutboxMessage m)->m.nextAttemptAt()==null?m.createdAt():m.nextAttemptAt())
                    .thenComparing(OutboxMessage::createdAt).thenComparing(m->m.messageId().toString()));
            assertEquals(expected,persistence.outbox().findPending(10));
            assertEquals(expected.subList(0,2),persistence.outbox().findPending(2));
        }
    }

    private static JdbcWorkflowPersistence persistence(PostgresTestDatabase.Schema schema) {
        return JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
    }
    private static void claim(PostgresTestDatabase.Schema schema,String table,UUID id,Instant until) throws Exception {
        try(Connection connection=schema.openConnection(); PreparedStatement statement=connection.prepareStatement("update "+table+" set status_value='CLAIMED',claimed_by='owner',claim_token='value-token',claim_until=? where id=?")) {
            statement.setBigDecimal(1,PostgresqlInstantCodec.encode(until)); statement.setString(2,id.toString()); assertEquals(1,statement.executeUpdate());
        }
    }
    private static void assertTime(PostgresTestDatabase.Schema schema,String table,String column,UUID id,Instant expected) throws Exception {
        try(Connection connection=schema.openConnection(); PreparedStatement statement=connection.prepareStatement("select "+column+" from "+table+" where id=?")) {
            statement.setString(1,id.toString()); try(ResultSet rows=statement.executeQuery()) { assertTrue(rows.next()); assertEquals(PostgresqlInstantCodec.encode(expected),rows.getBigDecimal(1)); }
        }
    }
}
