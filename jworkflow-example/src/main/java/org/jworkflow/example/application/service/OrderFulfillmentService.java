package org.jworkflow.example.application.service;

import org.jworkflow.example.application.command.ChargePaymentCommand;
import org.jworkflow.example.application.command.CreateOrderCommand;
import org.jworkflow.example.application.command.CreateShipmentCommand;
import org.jworkflow.example.application.command.ReserveInventoryCommand;
import org.jworkflow.example.application.result.InventoryReservationResult;
import org.jworkflow.example.application.result.OrderCreatedResult;
import org.jworkflow.example.application.result.PaymentChargeResult;
import org.jworkflow.example.application.result.ShipmentCreationResult;

/**
 * In-memory example application service handling order commands. Workflow adapters translate results into events;
 * this service is not a payment, inventory or shipment transport.
 */
public final class OrderFulfillmentService {
    /** Creates the stateless demonstration service without external transport dependencies. */
    public OrderFulfillmentService() {
        // Default construction requires no additional setup.
    }

    /**
     * Handles a demonstration order command and returns its successful result; this example performs no external
     * service call.
     * @param command command to validate and execute
     * @return the resulting order created result
     */
    public OrderCreatedResult handle(CreateOrderCommand command) {
        return new OrderCreatedResult(command.order().id());
    }

    /**
     * Handles a demonstration order command and returns its successful result; this example performs no external
     * service call.
     * @param command command to validate and execute
     * @return the resulting inventory reservation result
     */
    public InventoryReservationResult handle(ReserveInventoryCommand command) {
        return new InventoryReservationResult(command.order().id(), true);
    }

    /**
     * Handles a demonstration order command and returns its successful result; this example performs no external
     * service call.
     * @param command command to validate and execute
     * @return the resulting payment charge result
     */
    public PaymentChargeResult handle(ChargePaymentCommand command) {
        return new PaymentChargeResult(command.order().id(), true);
    }

    /**
     * Handles a demonstration order command and returns its successful result; this example performs no external
     * service call.
     * @param command command to validate and execute
     * @return the resulting shipment creation result
     */
    public ShipmentCreationResult handle(CreateShipmentCommand command) {
        return new ShipmentCreationResult(command.order().id(), true);
    }
}
