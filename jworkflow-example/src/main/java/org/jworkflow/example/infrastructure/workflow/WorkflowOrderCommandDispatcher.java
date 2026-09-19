package org.jworkflow.example.infrastructure.workflow;

import org.jworkflow.events.EventPublisher;
import org.jworkflow.example.application.command.ChargePaymentCommand;
import org.jworkflow.example.application.command.CreateOrderCommand;
import org.jworkflow.example.application.command.CreateShipmentCommand;
import org.jworkflow.example.application.command.OrderCommand;
import org.jworkflow.example.application.command.ReserveInventoryCommand;
import org.jworkflow.example.application.port.OrderCommandDispatcher;
import org.jworkflow.example.application.service.OrderFulfillmentService;

import java.util.Objects;

/**
 * Outbound workflow adapter: handles application commands and translates their results to workflow events.
 */
public final class WorkflowOrderCommandDispatcher implements OrderCommandDispatcher {
    private final OrderFulfillmentService service;
    private final EventPublisher eventPublisher;
    private final OrderWorkflowEventMapper eventMapper;

    /**
     * Constructs WorkflowOrderCommandDispatcher with the supplied collaborators and configuration.
     * @param service application order service invoked by the infrastructure adapter
     * @param eventPublisher host event sink; publication guarantees depend on the supplied implementation
     * @throws NullPointerException if service, eventPublisher is null
     */
    public WorkflowOrderCommandDispatcher(OrderFulfillmentService service, EventPublisher eventPublisher) {
        this.service = Objects.requireNonNull(service, "service");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.eventMapper = new OrderWorkflowEventMapper();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispatch(OrderCommand command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof CreateOrderCommand createOrder) {
            eventPublisher.publish(eventMapper.toEvent(service.handle(createOrder)));
        } else if (command instanceof ReserveInventoryCommand reserveInventory) {
            eventPublisher.publish(eventMapper.toEvent(service.handle(reserveInventory)));
        } else if (command instanceof ChargePaymentCommand chargePayment) {
            eventPublisher.publish(eventMapper.toEvent(service.handle(chargePayment)));
        } else if (command instanceof CreateShipmentCommand createShipment) {
            eventPublisher.publish(eventMapper.toEvent(service.handle(createShipment)));
        } else {
            throw new IllegalArgumentException("Unsupported order command: " + command.getClass().getName());
        }
    }
}
