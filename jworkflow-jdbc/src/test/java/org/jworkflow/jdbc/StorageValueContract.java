package org.jworkflow.jdbc;

import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.outbox.*;
import org.jworkflow.persistence.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shared value contracts only: no concurrent claims or command-idempotency assertions. */
final class StorageValueContract {
    static final List<Instant> TIMES = List.of(Instant.MIN, Instant.MAX, Instant.ofEpochSecond(-1,999_999_999),
            Instant.EPOCH, Instant.EPOCH.plusNanos(1), Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00.123456789Z"));

    static void payloads(JdbcWorkflowPersistence persistence) {
        byte[] large = new byte[1024 * 1024]; new Random(23).nextBytes(large);
        Map<String,Object> json = new LinkedHashMap<>();
        json.put("unicode", "日本語 🦊 café"); json.put("nullable",null);
        json.put("nested",Arrays.asList(null,Map.of("empty",List.of(),"truth",true),42));
        List<Object> payloads = Arrays.asList(null,new byte[0],new byte[]{0,-1,127},large,json, "");
        for (Object payload : payloads) {
            EventMessage envelope = new EventMessage(payload, payload instanceof byte[] ? "application/octet-stream":"application/json",
                    "schema-雪", "v1", true, Map.of("attribute", "é 🦊"));
            Instant at = Instant.ofEpochSecond(-1,999_999_999);
            WorkflowEvent event = event(WorkflowInstanceId.random(),envelope,at);
            persistence.events().append(event);
            WorkflowEvent read = persistence.events().find(event.metadata().eventId()).orElseThrow();
            assertEquals(event.metadata(), read.metadata()); assertEnvelope(envelope,read.message());
            InboxMessage inbox = inbox(envelope, at);
            assertTrue(persistence.inbox().insertIfAbsent(inbox).inserted());
            InboxMessage storedInbox = persistence.inbox().findById(inbox.messageId()).orElseThrow();
            assertEnvelope(envelope,storedInbox.message()); assertEquals(at,storedInbox.receivedAt());
            assertNull(storedInbox.nextAttemptAt()); assertNull(storedInbox.claimUntil());
            OutboxMessage outbox = outbox(envelope, at);
            persistence.outbox().enqueue(outbox);
            OutboxMessage storedOutbox = persistence.outbox().findById(outbox.messageId()).orElseThrow();
            assertEnvelope(envelope,storedOutbox.message()); assertEquals(at,storedOutbox.createdAt());
            assertNull(storedOutbox.publishedAt()); assertNull(storedOutbox.claimUntil());
        }
    }

    static void exactTimesAndRevisions(JdbcWorkflowPersistence persistence) {
        WorkflowDefinition first = WorkflowDefinition.of("revision-雪","1","one",WorkflowNode.end("one"));
        WorkflowDefinition second = WorkflowDefinition.of("revision-雪","1","two",WorkflowNode.end("two"));
        persistence.definitions().save(first); persistence.definitions().save(second);
        assertEquals(first,persistence.definitions().findRevision(first.name(),"1",first.revision()).orElseThrow());
        assertEquals(second,persistence.definitions().findRevision(second.name(),"1",second.revision()).orElseThrow());
        for (Instant time : TIMES) {
            WorkflowSnapshot snapshot = new WorkflowSnapshot(WorkflowInstanceId.random(),first.name(),"1",first.revision(),
                    "business", "correlation", "one",WorkflowStatus.WAITING,Map.of("time",time.toString()),4_294_967_296L,time,time);
            persistence.instances().insert(snapshot);
            assertEquals(snapshot,persistence.instances().findById(snapshot.instanceId()).orElseThrow());
            WorkflowSnapshot changed = snapshot.withLockVersion(snapshot.lockVersion()+1);
            assertEquals(changed,persistence.instances().update(changed,snapshot.lockVersion()));
            assertEquals(changed,persistence.instances().findById(snapshot.instanceId()).orElseThrow());
            assertThrows(WorkflowOptimisticLockException.class,()->persistence.instances().update(changed,snapshot.lockVersion()));
            WorkflowEvent event=event(snapshot.instanceId(),EventMessage.empty(),time);
            persistence.events().append(event); assertEquals(time,persistence.events().find(event.metadata().eventId()).orElseThrow().metadata().occurredAt());
            EventStatusAttempt status = new EventStatusAttempt(null,event.metadata().eventId(),null,1,"key",snapshot.instanceId(),"corr",
                    EventStatusScope.LISTENER,"handler","destination",EventStatusValue.FAILED,0,time,true,false,null,null,time);
            persistence.eventStatuses().append(status); assertEquals(status,persistence.eventStatuses().findAttempts(event.metadata().eventId()).get(0));
            WorkflowTimer timer=timer(snapshot.instanceId(),time,null);
            persistence.timers().save(timer); assertEquals(timer,persistence.timers().findByWorkflowInstance(snapshot.instanceId()).get(0));
            WorkflowTimerAttempt timerAttempt=new WorkflowTimerAttempt(null,timer.timerId(),1,WorkflowTimerStatus.RETRY_SCHEDULED,"owner",null,time);
            persistence.timers().appendAttempt(timerAttempt); assertEquals(timerAttempt,persistence.timers().findAttempts(timer.timerId()).get(0));
            InboxMessage inbox=new InboxMessage(null,UUID.randomUUID().toString(),"source",EventMessage.empty(),"corr","cause",time,time,InboxMessageStatus.CLAIMED,0,time,null,"owner",time);
            persistence.inbox().insertIfAbsent(inbox); assertEquals(inbox,persistence.inbox().findById(inbox.messageId()).orElseThrow());
            InboxAttempt ia=new InboxAttempt(null,inbox.messageId(),1,InboxMessageStatus.RETRY_SCHEDULED,null,null,time);
            persistence.inbox().appendAttempt(ia); assertEquals(ia,persistence.inbox().findAttempts(inbox.messageId()).get(0));
            OutboxMessage outbox=new OutboxMessage(null,UUID.randomUUID(),"destination",UUID.randomUUID().toString(),EventMessage.empty(),"corr","cause",time,time,OutboxMessageStatus.CLAIMED,0,time,null,"owner",time);
            persistence.outbox().enqueue(outbox); assertEquals(outbox,persistence.outbox().findById(outbox.messageId()).orElseThrow());
            OutboxAttempt oa=new OutboxAttempt(null,outbox.messageId(),1,OutboxMessageStatus.RETRY_SCHEDULED,null,null,time);
            persistence.outbox().appendAttempt(oa); assertEquals(oa,persistence.outbox().findAttempts(outbox.messageId()).get(0));
            CommandResultRecord command=new CommandResultRecord(UUID.randomUUID().toString(),"test","hash",snapshot.instanceId(),Map.of("instant",time.toString()),time);
            persistence.commandResults().save(command); assertEquals(command,persistence.commandResults().find(command.idempotencyKey()).orElseThrow());
        }
    }

    static WorkflowSnapshot snapshot(WorkflowInstanceId id, Instant time) {
        return new WorkflowSnapshot(id,"orders","1","exact-revision","business","corr","waiting",WorkflowStatus.WAITING,Map.of(),0,time,time);
    }
    static WorkflowTimer timer(WorkflowInstanceId id, Instant due, Instant next) {
        return new WorkflowTimer(null,id,"step",due,"next",new EventName("timer.fired"),WorkflowTimerStatus.PENDING,0,next,null,null,due,due);
    }
    static InboxMessage inbox(EventMessage message,Instant at) {
        return new InboxMessage(null,UUID.randomUUID().toString(),"source",message,"corr","cause",at,null,InboxMessageStatus.RECEIVED,0,null,null,null,null);
    }
    static OutboxMessage outbox(EventMessage message,Instant at) {
        return new OutboxMessage(null,UUID.randomUUID(),"destination",UUID.randomUUID().toString(),message,"corr","cause",at,null,OutboxMessageStatus.PENDING,0,null,null,null,null);
    }
    static WorkflowEvent event(WorkflowInstanceId id,EventMessage message,Instant at) {
        return new WorkflowEvent(new EventMetadata(UUID.randomUUID(),new EventName("order.created"),"source","corr","cause","trace",id,"business","tenant","1",at,at,Map.of("header","雪")),message);
    }
    private static void assertEnvelope(EventMessage expected,EventMessage actual) {
        if(expected.payload() instanceof byte[] bytes) assertArrayEquals(bytes,(byte[])actual.payload());
        else assertEquals(expected.payload(),actual.payload());
        assertEquals(expected.contentType(),actual.contentType()); assertEquals(expected.schemaName(),actual.schemaName());
        assertEquals(expected.schemaVersion(),actual.schemaVersion()); assertEquals(expected.redacted(),actual.redacted());
        assertEquals(expected.attributes(),actual.attributes());
    }
}
