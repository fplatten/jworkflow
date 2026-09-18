package org.jworkflow.persistence;

import org.jworkflow.events.EventMessage;
import org.jworkflow.inbox.InboxMessage;
import org.jworkflow.inbox.InboxMessageStatus;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.outbox.OutboxMessage;
import org.jworkflow.outbox.OutboxMessageStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dependency-free contract checks executed by Maven. */
public final class PersistenceContractTest {
    public static void main(String[] args) {
        snapshotCarriesRecoveryAndConcurrencyState();
        snapshotCopiesNestedNullValues();
        transactionManagerReturnsResults();
        messagesPreserveTransportMetadata();
        optimisticLockErrorIsTypedAndActionable();
        corePersistencePortsDoNotExposeJdbcTypes();
    }

    private static void snapshotCarriesRecoveryAndConcurrencyState() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        WorkflowInstanceId id = WorkflowInstanceId.random();
        WorkflowSnapshot snapshot = new WorkflowSnapshot(id, "orders", "2", "sha256:abc", "order-7",
                "corr-7", "awaiting-payment", WorkflowStatus.WAITING, Map.of(), 0, created, created);
        WorkflowSnapshot updated = snapshot.withLockVersion(1);
        check("sha256:abc".equals(updated.workflowRevision()), "revision must be retained");
        check("corr-7".equals(updated.correlationId()), "correlation ID must be retained");
        check(updated.lockVersion() == 1, "lock version must evolve explicitly");
        expect(IllegalArgumentException.class, () -> snapshot.withLockVersion(-1));
        expect(IllegalArgumentException.class, () -> new WorkflowSnapshot(id, "orders", "2", " ", "order-7",
                null, "state", WorkflowStatus.RUNNING, Map.of(), 0, created, created));
    }

    private static void snapshotCopiesNestedNullValues() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<Object> values = new ArrayList<>();
        values.add(null);
        values.add(Map.of("amount", new BigDecimal("12.50")));
        nested.put("values", values);
        nested.put("nullable", null);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        WorkflowSnapshot snapshot = new WorkflowSnapshot(WorkflowInstanceId.random(), "orders", "1", "rev-1",
                "order-1", null, "created", WorkflowStatus.RUNNING, nested, 0, now, now);
        values.add("later mutation");
        check(snapshot.variables().containsKey("nullable") && snapshot.variables().get("nullable") == null,
                "top-level null must be preserved");
        check(((List<?>) snapshot.variables().get("values")).size() == 2, "nested collections must be copied");
    }

    private static void transactionManagerReturnsResults() {
        WorkflowTransactionManager manager = WorkflowTransaction::execute;
        check("value".equals(manager.inTransaction(() -> "value")), "result-bearing transaction must return work result");
    }

    private static void messagesPreserveTransportMetadata() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        EventMessage event = new EventMessage(Map.of("order", 7), "application/json", "order.created", "3", false,
                Map.of("tenant", "north"));
        InboxMessage inbox = new InboxMessage(UUID.randomUUID(), "external-7", "erp", event, "corr-7", "cause-6",
                now, null, InboxMessageStatus.RECEIVED, 0, null, null, null, null);
        InboxMessage duplicateIdentity = new InboxMessage(UUID.randomUUID(), "external-7", "erp", event, null, null,
                now, null, InboxMessageStatus.RECEIVED, 0, null, null, null, null);
        check(inbox.deduplicationKey().equals(duplicateIdentity.deduplicationKey()),
                "inbox identity must be source system plus external event ID");
        OutboxMessage outbox = new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), "orders", "publish-7", event,
                "corr-7", "cause-6", now, null, OutboxMessageStatus.PENDING, 0, null, null, null, null);
        check(outbox.message().schemaVersion().equals("3") && outbox.destination().equals("orders")
                        && outbox.idempotencyKey().equals("publish-7"),
                "outbox must preserve schema, destination, and idempotency metadata");
    }

    private static void optimisticLockErrorIsTypedAndActionable() {
        WorkflowInstanceId id = WorkflowInstanceId.random();
        WorkflowOptimisticLockException error = new WorkflowOptimisticLockException(id, 4, 5L);
        check(error.instanceId().equals(id) && error.expectedVersion() == 4 && error.observedVersion() == 5,
                "optimistic-lock error must expose conflict details");
    }

    private static void corePersistencePortsDoNotExposeJdbcTypes() {
        for (Class<?> port : List.of(WorkflowInstanceRepository.class, WorkflowDefinitionRepository.class,
                WorkflowTimerRepository.class, InboxRepository.class, OutboxRepository.class,
                WorkflowTransactionManager.class)) {
            for (Method method : port.getMethods()) {
                check(!method.getReturnType().getName().startsWith("java.sql")
                                && !method.getReturnType().getName().startsWith("javax.sql"),
                        "JDBC return type leaked from " + method);
                for (Class<?> parameter : method.getParameterTypes()) {
                    check(!parameter.getName().startsWith("java.sql") && !parameter.getName().startsWith("javax.sql"),
                            "JDBC parameter leaked from " + method);
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        try {
            work.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw failure;
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
