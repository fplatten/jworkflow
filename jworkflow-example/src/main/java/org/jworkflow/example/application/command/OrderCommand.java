package org.jworkflow.example.application.command;

public sealed interface OrderCommand permits CreateOrderCommand, ReserveInventoryCommand,
        ChargePaymentCommand, CreateShipmentCommand {
}
