package org.jworkflow.example.application.result;

/**
 * Application result describing the inventory reservation for an order.
 * @param orderId application order identity
 * @param reserved whether the example inventory reservation succeeded
 */
public record InventoryReservationResult(String orderId, boolean reserved) {
}
