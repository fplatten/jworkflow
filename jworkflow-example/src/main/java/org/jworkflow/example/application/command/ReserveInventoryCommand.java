package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

/**
 * Application request to reserve inventory for an order.
 * @param order order value returned by the application service
 */
public record ReserveInventoryCommand(Order order) implements OrderCommand {
    /**
     * Creates this value from the supplied components.
     * @param order order value returned by the application service
     * @throws NullPointerException if order is null
     */
    public ReserveInventoryCommand {
        Objects.requireNonNull(order, "order");
    }
}
