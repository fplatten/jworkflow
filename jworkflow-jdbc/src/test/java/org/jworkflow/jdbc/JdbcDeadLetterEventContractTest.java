package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventMessage;
import org.jworkflow.inbox.InboxMessage;
import org.jworkflow.inbox.InboxMessageStatus;
import org.jworkflow.model.*;
import org.jworkflow.observability.WorkflowLifecycleEvent;
import org.jworkflow.observability.WorkflowLifecycleEventType;
import org.jworkflow.outbox.OutboxMessageStatus;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every way a message can be dead-lettered is observable, with the message id: an outbox message whose publication
 * keeps failing, and an outbox or inbox message whose worker's lease keeps expiring.
 */
public final class JdbcDeadLetterEventContractTest {
    public static void main(String[] args) throws Exception {
        outboxPublicationExhaustionIsObservable();
        outboxLeaseExpiryExhaustionIsObservable();
        inboxLeaseExpiryExhaustionIsObservable();
    }

    private static void outboxPublicationExhaustionIsObservable() throws Exception {
        Fixture f = fixture("outbox-publication");
        try (JdbcWorkflowEngine engine = engine(f, definition())) {
            engine.start("dead-letter-flow", "order-p", Map.of());
            UUID id = outboxId(f);
            try (JdbcOutboxApplication app = engine.outbox(Map.of("workflow.events", m -> { throw new IllegalStateException("down"); }))) {
                app.pollOnce();
                check(app.find(id).orElseThrow().status() == OutboxMessageStatus.DEAD_LETTER, "exhausted publication must dead-letter");
            }
            WorkflowLifecycleEvent event = single(f.events, WorkflowLifecycleEventType.OUTBOX_DEAD_LETTERED);
            check(id.toString().equals(event.attributes().get("messageId")), "event must name the message: " + event.attributes());
            check("publication_failed".equals(event.attributes().get("failureCategory")), "category: " + event.attributes());
            check("workflow.events".equals(event.attributes().get("destination")), "destination: " + event.attributes());
        }
    }

    private static void outboxLeaseExpiryExhaustionIsObservable() throws Exception {
        Fixture f = fixture("outbox-lease");
        UUID id;
        try (JdbcWorkflowEngine seed = engine(f, definition())) {
            seed.start("dead-letter-flow", "order-l", Map.of());
            id = outboxId(f);
        }
        JdbcWorkflowPersistence ports = ContractBackend.persistence(f.url, null, null, null, null, false, Map.of());
        ports.jdbcTransactions().inImmediateTransaction(() -> ports.outbox().claimEligible(f.clock.instant(), "crashed", f.clock.instant().plusSeconds(5), 1));
        f.clock.advance(Duration.ofSeconds(6));
        try (JdbcWorkflowEngine engine = engine(f, null);
             JdbcOutboxApplication app = engine.outbox(Map.of("workflow.events", m -> { }))) {
            app.pollOnce();
            check(app.find(id).orElseThrow().status() == OutboxMessageStatus.DEAD_LETTER, "exhausted lease must dead-letter");
        }
        WorkflowLifecycleEvent event = single(f.events, WorkflowLifecycleEventType.OUTBOX_DEAD_LETTERED);
        check(id.toString().equals(event.attributes().get("messageId")), "event must name the message: " + event.attributes());
        check("lease_expired".equals(event.attributes().get("failureCategory")), "category: " + event.attributes());
    }

    private static void inboxLeaseExpiryExhaustionIsObservable() throws Exception {
        Fixture f = fixture("inbox-lease");
        try (JdbcWorkflowEngine seed = engine(f, definition())) { /* creates the schema */ }
        JdbcWorkflowPersistence ports = ContractBackend.persistence(f.url, null, null, null, null, false, Map.of());
        InboxMessage message = new InboxMessage(null, "external-1", "erp",
                new EventMessage(Map.of("businessKey", "order-i"), "application/json", "order-event", "1", false, Map.of()),
                "corr-order-i", "cause-order-i", null, null, null, 0, null, null, null, null);
        UUID id = ports.transactions().inTransaction(() -> ports.inbox().insertIfAbsent(message)).message().messageId();
        ports.jdbcTransactions().inImmediateTransaction(() -> ports.inbox().claimEligible(f.clock.instant(), "crashed", f.clock.instant().plusSeconds(5), 1));
        f.clock.advance(Duration.ofSeconds(6));
        try (JdbcWorkflowEngine engine = engine(f, null);
             JdbcInboxApplication app = engine.inbox(m -> List.of())) {
            app.pollOnce();
            check(app.find(id).orElseThrow().status() == InboxMessageStatus.DEAD_LETTER, "exhausted lease must dead-letter");
        }
        WorkflowLifecycleEvent event = single(f.events, WorkflowLifecycleEventType.INBOX_DEAD_LETTERED);
        check(id.toString().equals(event.attributes().get("messageId")), "event must name the message: " + event.attributes());
        check("lease_expired".equals(event.attributes().get("failureCategory")), "category: " + event.attributes());
    }

    private static WorkflowLifecycleEvent single(List<WorkflowLifecycleEvent> events, WorkflowLifecycleEventType type) {
        List<WorkflowLifecycleEvent> matching = events.stream().filter(e -> e.type() == type).toList();
        check(matching.size() == 1, "expected exactly one " + type + " event, got " + matching.size());
        return matching.get(0);
    }
    private static WorkflowDefinition definition() {
        return WorkflowDefinition.of("dead-letter-flow", "1", "pending",
                WorkflowNode.step("pending", "work.do", List.of(WorkflowTransition.goTo("done"))), WorkflowNode.end("done"));
    }
    private static JdbcWorkflowEngine engine(Fixture f, WorkflowDefinition definition) {
        WorkflowEngineBuilder builder = ContractBackend.engine(f.url).initialize(true).clock(f.clock).timerPolling(false)
                .lifecycleObserver(f.events::add)
                .setting("outbox.max-attempts", "1").setting("inbox.max-attempts", "1");
        if (definition != null) builder.definition(definition);
        return (JdbcWorkflowEngine) builder.build();
    }
    private static UUID outboxId(Fixture f) throws Exception {
        try (Connection c = ContractBackend.open(f.url); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select id from workflow_outbox")) {
            check(r.next(), "outbox row must exist");
            return UUID.fromString(r.getString(1));
        }
    }
    private static Fixture fixture(String name) throws Exception {
        return new Fixture(ContractBackend.url(Files.createTempFile("jworkflow-dead-letter-" + name + "-", ".sqlite").toAbsolutePath()),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")), new CopyOnWriteArrayList<>());
    }
    private record Fixture(String url, MutableClock clock, List<WorkflowLifecycleEvent> events) {}
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
