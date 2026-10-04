package org.jworkflow.jdbc;

import org.jworkflow.events.EventMessage;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.outbox.OutboxMessage;
import org.jworkflow.outbox.OutboxMessageStatus;
import org.jworkflow.query.OutboxStateView;
import org.jworkflow.query.WorkflowQueryService;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

/**
 * Operators can list every dead-lettered outbox message, filtered by destination and paged, however many other
 * messages the outbox holds.
 */
public final class JdbcDeadLetterQueryContractTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    public static void main(String[] args) throws Exception {
        String url = ContractBackend.url(Files.createTempFile("jworkflow-dead-letter-query-", ".sqlite").toAbsolutePath());
        try (JdbcWorkflowEngine engine = (JdbcWorkflowEngine) ContractBackend.engine(url).initialize(true).timerPolling(false)
                .definition(WorkflowDefinition.of("query-flow", "1", "done", WorkflowNode.end("done"))).build()) {
            JdbcWorkflowPersistence ports = ContractBackend.persistence(url, null, null, null, null, false, Map.of());
            Set<UUID> deadA = new HashSet<>(), deadB = new HashSet<>();
            for (int i = 0; i < 5; i++) deadA.add(enqueue(ports, "orders", "a-" + i));
            for (int i = 0; i < 3; i++) deadB.add(enqueue(ports, "billing", "b-" + i));
            UUID pending = enqueue(ports, "orders", "pending");
            deadLetterAllExcept(url, pending);

            WorkflowQueryService queries = engine.queries();
            List<OutboxStateView> orders = readAllPages(queries, "orders", 2);
            check(ids(orders).equals(deadA), "orders dead letters must be returned exactly once across pages: " + ids(orders));
            check(orders.stream().allMatch(v -> v.status() == OutboxMessageStatus.DEAD_LETTER && "orders".equals(v.destination())),
                    "only dead-lettered orders messages may be returned");
            check(!ids(orders).contains(pending), "pending messages must not be returned");
            List<UUID> order = orders.stream().map(OutboxStateView::messageId).toList();
            check(order.equals(order.stream().sorted(Comparator.comparing(UUID::toString)).toList()), "pages must be ordered by message id");

            Set<UUID> all = new HashSet<>(deadA);
            all.addAll(deadB);
            check(ids(readAllPages(queries, null, 3)).equals(all), "a null destination must return every dead letter");
            check(queries.deadLetteredOutbox("nowhere", null, 10).isEmpty(), "an unknown destination returns nothing");
            try {
                queries.deadLetteredOutbox(null, null, 0);
                throw new AssertionError("a non-positive limit must be rejected");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    private static List<OutboxStateView> readAllPages(WorkflowQueryService queries, String destination, int pageSize) {
        List<OutboxStateView> all = new ArrayList<>();
        UUID after = null;
        for (int page = 0; page < 100; page++) {
            List<OutboxStateView> next = queries.deadLetteredOutbox(destination, after, pageSize);
            check(next.size() <= pageSize, "a page must respect the limit");
            if (next.isEmpty()) return all;
            all.addAll(next);
            after = next.get(next.size() - 1).messageId();
        }
        throw new AssertionError("paging did not terminate");
    }
    private static Set<UUID> ids(List<OutboxStateView> views) {
        Set<UUID> ids = new HashSet<>();
        for (OutboxStateView view : views) check(ids.add(view.messageId()), "duplicate message across pages: " + view.messageId());
        return ids;
    }
    private static UUID enqueue(JdbcWorkflowPersistence ports, String destination, String key) {
        OutboxMessage message = new OutboxMessage(null, UUID.randomUUID(), destination, key,
                new EventMessage(Map.of("value", 1), "application/json", "outbox", "1", false, Map.of()),
                "corr", "cause", NOW, null, OutboxMessageStatus.PENDING, 0, null, null, null, null);
        return ports.transactions().inTransaction(() -> ports.outbox().enqueue(message)).messageId();
    }
    private static void deadLetterAllExcept(String url, UUID pending) throws Exception {
        try (Connection c = ContractBackend.open(url);
             PreparedStatement s = c.prepareStatement("update workflow_outbox set status_value='DEAD_LETTER' where id<>?")) {
            s.setString(1, pending.toString());
            s.executeUpdate();
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
