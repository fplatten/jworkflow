package org.jworkflow.example;

import org.jworkflow.definition.ClasspathWorkflowDefinitionSource;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.events.Events;
import org.jworkflow.example.application.command.CreateOrderCommand;
import org.jworkflow.example.application.port.OrderCommandDispatcher;
import org.jworkflow.example.application.service.OrderFulfillmentService;
import org.jworkflow.example.infrastructure.workflow.InventoryReservationListener;
import org.jworkflow.example.infrastructure.workflow.PaymentChargeListener;
import org.jworkflow.example.infrastructure.workflow.ShipmentCreationListener;
import org.jworkflow.example.infrastructure.workflow.WorkflowOrderCommandDispatcher;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ParallelOrderRoutingExampleTest {
    private static final Set<String> ORDER_IDS = Set.of("order-parallel-1001", "order-parallel-1002");

    public static void main(String[] args) throws Exception {
        WorkflowEngine.clearInstance();
        OrderFulfillmentService orderService = new OrderFulfillmentService();
        OrderCommandDispatcher commandDispatcher = new WorkflowOrderCommandDispatcher(orderService, Events::publish);

        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definitions(new ClasspathWorkflowDefinitionSource(List.of("workflows/order-fulfillment.groovy")))
                .listener("inventoryReservationListener", new InventoryReservationListener(commandDispatcher))
                .listener("paymentChargeListener", new PaymentChargeListener(commandDispatcher))
                .listener("shipmentCreationListener", new ShipmentCreationListener(commandDispatcher))
                .buildAndSetInstance()) {
            dispatchInParallel(commandDispatcher);
            assertOrdersRoutedIndependently(awaitCompletedOrders(engine));
        } finally {
            WorkflowEngine.clearInstance();
        }
    }

    private static void dispatchInParallel(OrderCommandDispatcher commandDispatcher) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(ORDER_IDS.size());
        try {
            List<Future<Object>> dispatches = ORDER_IDS.stream()
                    .map(orderId -> clients.submit(() -> {
                        start.await();
                        commandDispatcher.dispatch(new CreateOrderCommand(orderId));
                        return null;
                    }))
                    .toList();

            start.countDown();
            for (Future<Object> dispatch : dispatches) {
                dispatch.get();
            }
        } finally {
            clients.shutdownNow();
        }
    }

    private static List<WorkflowSnapshot> awaitCompletedOrders(WorkflowEngine engine) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            List<WorkflowSnapshot> workflows = engine.context().getWorkflows();
            if (workflows.size() == ORDER_IDS.size()
                    && workflows.stream().allMatch(workflow -> workflow.status() == WorkflowStatus.COMPLETED)) {
                return workflows;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("Timed out waiting for both order workflows to complete");
    }

    private static void assertOrdersRoutedIndependently(List<WorkflowSnapshot> workflows) {
        Map<String, WorkflowSnapshot> workflowByOrder = workflows.stream()
                .collect(Collectors.toMap(WorkflowSnapshot::businessKey, Function.identity()));

        if (!workflowByOrder.keySet().equals(ORDER_IDS)) {
            throw new AssertionError("Expected workflows for " + ORDER_IDS + ", got " + workflowByOrder.keySet());
        }

        for (String orderId : ORDER_IDS) {
            WorkflowSnapshot workflow = workflowByOrder.get(orderId);
            if (!orderId.equals(workflow.variables().get("orderId"))) {
                throw new AssertionError("Workflow " + orderId + " received another order's event data: "
                        + workflow.variables());
            }
            if (!"completed".equals(workflow.state())) {
                throw new AssertionError("Workflow " + orderId + " ended at " + workflow.state());
            }
            if (!Boolean.TRUE.equals(workflow.variables().get("inventoryReserved"))
                    || !Boolean.TRUE.equals(workflow.variables().get("paymentCharged"))
                    || !Boolean.TRUE.equals(workflow.variables().get("shipmentCreated"))) {
                throw new AssertionError("Workflow " + orderId + " did not receive all routed events: "
                        + workflow.variables());
            }
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
