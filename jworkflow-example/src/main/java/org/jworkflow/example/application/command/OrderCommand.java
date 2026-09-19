package org.jworkflow.example.application.command;

/**
 * Marker for the order example's application commands; domain code does not depend on workflow events.
 */
public sealed interface OrderCommand permits CreateOrderCommand, ReserveInventoryCommand,
        ChargePaymentCommand, CreateShipmentCommand {
}
