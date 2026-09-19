package org.jworkflow.example.application.result;

/**
 * Application result describing an order payment charge.
 * @param orderId application order identity
 * @param charged whether the example payment charge succeeded
 */
public record PaymentChargeResult(String orderId, boolean charged) {
}
