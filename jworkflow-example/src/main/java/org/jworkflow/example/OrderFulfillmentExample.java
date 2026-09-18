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
import java.util.logging.Logger;

public final class OrderFulfillmentExample {
    private static final Logger LOGGER = Logger.getLogger(OrderFulfillmentExample.class.getName());
    private OrderFulfillmentExample() {
    }

    public static void runSuccessfulOrder(OrderCommandDispatcher commandDispatcher) {
        commandDispatcher.dispatch(new CreateOrderCommand("order-1001"));
    }

    public static WorkflowSnapshot runSuccessfulOrder() throws ClassNotFoundException, InterruptedException {
        WorkflowEngine.clearInstance();
        OrderFulfillmentService orderService = new OrderFulfillmentService();
        OrderCommandDispatcher commandDispatcher = new WorkflowOrderCommandDispatcher(orderService, Events::publish);
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definitions(new ClasspathWorkflowDefinitionSource(List.of("workflows/order-fulfillment.groovy")))
                .listener("inventoryReservationListener", new InventoryReservationListener(commandDispatcher))
                .listener("paymentChargeListener", new PaymentChargeListener(commandDispatcher))
                .listener("shipmentCreationListener", new ShipmentCreationListener(commandDispatcher))
                .buildAndSetInstance()) {

            runSuccessfulOrder(commandDispatcher);
            return awaitAllWorkflowsCompleted();
        } finally {
            WorkflowEngine.clearInstance();
        }
    }

    public static void main(String[] args) throws Exception {
        WorkflowEngine.clearInstance();
        OrderFulfillmentService orderService = new OrderFulfillmentService();
        OrderCommandDispatcher commandDispatcher = new WorkflowOrderCommandDispatcher(orderService, Events::publish);
        try (WorkflowEngine ignored = WorkflowEngine.builder()
                .definitions(new ClasspathWorkflowDefinitionSource(List.of("workflows/order-fulfillment.groovy")))
                .listener("inventoryReservationListener", new InventoryReservationListener(commandDispatcher))
                .listener("paymentChargeListener", new PaymentChargeListener(commandDispatcher))
                .listener("shipmentCreationListener", new ShipmentCreationListener(commandDispatcher))
                .buildAndSetInstance()) {
            runSuccessfulOrder(commandDispatcher);
            WorkflowSnapshot snapshot = awaitAllWorkflowsCompleted();
            LOGGER.info(() -> snapshot.workflowKey() + " " + snapshot.businessKey() + " -> " + snapshot.state());
        } finally {
            WorkflowEngine.clearInstance();
        }
    }

    private static WorkflowSnapshot awaitAllWorkflowsCompleted() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            List<WorkflowSnapshot> workflows = WorkflowEngine.instance().context().getWorkflows();
            if (!workflows.isEmpty()
                    && workflows.stream().allMatch(workflow -> workflow.status() == WorkflowStatus.COMPLETED)) {
                return workflows.get(0);
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for all workflows to complete");
    }
}
