package org.jworkflow.example.infrastructure.workflow;

import org.jworkflow.events.EventMessage;
import org.jworkflow.events.EventMetadata;
import org.jworkflow.events.EventName;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.example.application.result.InventoryReservationResult;
import org.jworkflow.example.application.result.OrderCreatedResult;
import org.jworkflow.example.application.result.PaymentChargeResult;
import org.jworkflow.example.application.result.ShipmentCreationResult;
import org.jworkflow.example.domain.Order;

import java.time.Instant;
import java.util.Map;

/**
 * Translates application command results to integration events without exposing workflow types to the domain
 * model.
 */
final class OrderWorkflowEventMapper {
    private static final String TEXT_ORDER_ID = "orderId";
    Order toOrder(WorkflowEvent event) {
        Object value = event.message().payload() instanceof Map<?, ?> payload
                ? payload.get(TEXT_ORDER_ID)
                : null;
        if (value == null) {
            value = event.metadata().headers().get(TEXT_ORDER_ID);
        }
        if (value == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        return new Order(value.toString());
    }

    WorkflowEvent toEvent(OrderCreatedResult result) {
        return correlatedEvent(OrderEvents.ORDER_CREATED, result.orderId(), Map.of(TEXT_ORDER_ID, result.orderId()));
    }

    WorkflowEvent toEvent(InventoryReservationResult result) {
        return event(OrderEvents.INVENTORY_RESERVED, Map.of(
                TEXT_ORDER_ID, result.orderId(),
                "inventoryReserved", result.reserved()));
    }

    WorkflowEvent toEvent(PaymentChargeResult result) {
        return event(OrderEvents.PAYMENT_CHARGED, Map.of(
                TEXT_ORDER_ID, result.orderId(),
                "paymentCharged", result.charged()));
    }

    WorkflowEvent toEvent(ShipmentCreationResult result) {
        return event(OrderEvents.SHIPMENT_CREATED, Map.of(
                TEXT_ORDER_ID, result.orderId(),
                "shipmentCreated", result.created()));
    }

    private static WorkflowEvent event(OrderEvents event, Map<String, Object> payload) {
        return WorkflowEvent.named(event.value(), payload);
    }

    private static WorkflowEvent correlatedEvent(
            OrderEvents event,
            String orderId,
            Map<String, Object> payload
    ) {
        Instant now = Instant.now();
        return new WorkflowEvent(
                new EventMetadata(
                        null,
                        new EventName(event.value()),
                        "order-service",
                        "correlation-" + orderId,
                        null,
                        null,
                        null,
                        orderId,
                        null,
                        "1",
                        now,
                        now,
                        Map.of(TEXT_ORDER_ID, orderId)),
                EventMessage.json(payload));
    }
}
