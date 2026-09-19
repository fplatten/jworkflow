package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

/**
 * Application request to create a shipment for an order.
 * @param order order value returned by the application service
 */
public record CreateShipmentCommand(Order order) implements OrderCommand {
    /**
     * Creates this value from the supplied components.
     * @param order order value returned by the application service
     * @throws NullPointerException if order is null
     */
    public CreateShipmentCommand {
        Objects.requireNonNull(order, "order");
    }
}
