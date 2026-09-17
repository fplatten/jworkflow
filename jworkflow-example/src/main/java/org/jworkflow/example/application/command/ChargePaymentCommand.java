package org.jworkflow.example.application.command;

import org.jworkflow.example.domain.Order;

import java.util.Objects;

public record ChargePaymentCommand(Order order) implements OrderCommand {
    public ChargePaymentCommand {
        Objects.requireNonNull(order, "order");
    }
}
