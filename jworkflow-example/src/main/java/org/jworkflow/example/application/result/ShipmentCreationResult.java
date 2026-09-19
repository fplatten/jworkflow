package org.jworkflow.example.application.result;

/**
 * Application result describing shipment creation for an order.
 * @param orderId application order identity
 * @param created whether the example shipment was created
 */
public record ShipmentCreationResult(String orderId, boolean created) {
}
