package org.jworkflow.example.application.service;

import org.jworkflow.example.application.command.ChargePaymentCommand;
import org.jworkflow.example.application.command.CreateOrderCommand;
import org.jworkflow.example.application.command.CreateShipmentCommand;
import org.jworkflow.example.application.command.ReserveInventoryCommand;
import org.jworkflow.example.application.result.InventoryReservationResult;
import org.jworkflow.example.application.result.OrderCreatedResult;
import org.jworkflow.example.application.result.PaymentChargeResult;
import org.jworkflow.example.application.result.ShipmentCreationResult;

public final class OrderFulfillmentService {
    public OrderCreatedResult handle(CreateOrderCommand command) {
        return new OrderCreatedResult(command.order().id());
    }

    public InventoryReservationResult handle(ReserveInventoryCommand command) {
        return new InventoryReservationResult(command.order().id(), true);
    }

    public PaymentChargeResult handle(ChargePaymentCommand command) {
        return new PaymentChargeResult(command.order().id(), true);
    }

    public ShipmentCreationResult handle(CreateShipmentCommand command) {
        return new ShipmentCreationResult(command.order().id(), true);
    }
}
