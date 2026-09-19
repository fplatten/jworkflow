package org.jworkflow.example.application.result;

/**
 * Application result containing the newly created order identity and data.
 * @param orderId application order identity
 */
public record OrderCreatedResult(String orderId) {
}
