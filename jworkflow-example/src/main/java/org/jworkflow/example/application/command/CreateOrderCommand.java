package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

/**
 * Client-facing request to create an order before publishing workflow integration events.
 * @param order order value returned by the application service
 */
public record CreateOrderCommand(Order order) implements OrderCommand {
    /**
     * Creates this value from the supplied components.
     * @param order order value returned by the application service
     * @throws NullPointerException if order is null
     */
    public CreateOrderCommand {
        Objects.requireNonNull(order, "order");
    }

    /**
     * Creates this value from the supplied components.
     * @param orderId application order identity
     */
    public CreateOrderCommand(String orderId) {
        this(new Order(orderId));
    }
}
