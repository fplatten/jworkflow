package org.jworkflow.example.infrastructure.workflow;

enum OrderEvents {
    ORDER_CREATED("order.created"),
    INVENTORY_RESERVED("inventory.reserved"),
    PAYMENT_CHARGED("payment.charged"),
    SHIPMENT_CREATED("shipment.created");

    private final String value;

    OrderEvents(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
