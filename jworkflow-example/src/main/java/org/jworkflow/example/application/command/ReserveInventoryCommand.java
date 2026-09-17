package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

public record ReserveInventoryCommand(Order order) implements OrderCommand {
    public ReserveInventoryCommand {
        Objects.requireNonNull(order, "order");
    }
}
