package org.jworkflow.jdbc;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.StepResult;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.engine.WorkflowEngineBuilder;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Publishing an event has the same effect in memory and in a database: it starts the workflow whose start event it
 * is, correlates later events by the definition's {@code correlateBy} field, and has finished when publish returns.
 */
public final class JdbcPublishParityContractTest {
    public static void main(String[] args) throws Exception {
        String url = ContractBackend.url(Files.createTempFile("jworkflow-publish-parity-", ".sqlite").toAbsolutePath());
        check("in-memory", WorkflowEngine::builder);
        check("durable", () -> ContractBackend.engine(url).initialize(true).timerPolling(false));
    }

    private static void check(String backend, Supplier<WorkflowEngineBuilder> base) {
        AtomicInteger reservations = new AtomicInteger();
        try (WorkflowEngine engine = base.get()
                .definition(WorkflowDefinitionBuilder.workflow("parity-order").version("1")
                        .startWhen("order.placed").correlateBy("orderId")
                        .step("reserveStock", step -> step.action("stock.reserve").onSuccess("awaitPayment"))
                        .waitFor("awaitPayment", wait -> wait.event("payment.received").then("shipped"))
                        .end("shipped"))
                .stepHandler("stock.reserve", context -> {
                    reservations.incrementAndGet();
                    return StepResult.success(Map.of("reserved", context.variables().get("orderId")));
                })
                .build()) {
            engine.publish(WorkflowEvent.named("order.placed", Map.of("orderId", "order-1", "total", 42)));
            WorkflowSnapshot placed = engine.snapshot("parity-order", "order-1");
            require(backend, "awaitPayment".equals(placed.state()), "the start step must have run when publish returns: " + placed.state());
            require(backend, "order-1".equals(placed.variables().get("reserved")), "the step must see the event's payload: " + placed.variables());
            require(backend, Integer.valueOf(42).equals(((Number) placed.variables().get("total")).intValue()), "payload fields become variables");

            engine.publish(WorkflowEvent.named("order.placed", Map.of("orderId", "order-1")));
            require(backend, reservations.get() == 1, "a repeated start event must not start or run the workflow again");

            engine.publish(WorkflowEvent.named("payment.received", Map.of("orderId", "someone-else")));
            require(backend, engine.snapshot("parity-order", "order-1").status() != WorkflowStatus.COMPLETED,
                    "an event for another business key must not be delivered");

            engine.publish(WorkflowEvent.named("unrelated.happened", Map.of("orderId", "order-1")));

            engine.publish(WorkflowEvent.named("payment.received", Map.of("orderId", "order-1")));
            require(backend, engine.snapshot("parity-order", "order-1").status() == WorkflowStatus.COMPLETED,
                    "the correlated event must complete the workflow when publish returns");

            try {
                engine.publish(WorkflowEvent.named("order.placed", Map.of("customer", "c-1")));
                throw new AssertionError(backend + ": a start event without its correlation field must be rejected");
            } catch (IllegalArgumentException expected) {
                require(backend, expected.getMessage().contains("orderId"), "the message must name the field: " + expected.getMessage());
            }
            require(backend, List.of(1).equals(List.of(reservations.get())), "no other step may have run");
        }
    }

    private static void require(String backend, boolean condition, String message) {
        if (!condition) throw new AssertionError(backend + ": " + message);
    }

    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
