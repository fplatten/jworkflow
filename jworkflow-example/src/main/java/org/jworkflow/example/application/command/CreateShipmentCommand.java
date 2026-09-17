package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

public record CreateShipmentCommand(Order order) implements OrderCommand {
    public CreateShipmentCommand {
        Objects.requireNonNull(order, "order");
    }
}
