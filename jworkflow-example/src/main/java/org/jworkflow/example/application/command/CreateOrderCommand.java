package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

public record CreateOrderCommand(Order order) implements OrderCommand {
    public CreateOrderCommand {
        Objects.requireNonNull(order, "order");
    }

    public CreateOrderCommand(String orderId) {
        this(new Order(orderId));
    }
}
