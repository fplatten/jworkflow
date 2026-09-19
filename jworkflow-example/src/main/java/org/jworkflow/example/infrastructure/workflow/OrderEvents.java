package org.jworkflow.example.infrastructure.workflow;

/**
 * Named integration events shared by the order example's infrastructure adapters.
 */
enum OrderEvents {
    /**
     * The demonstration order was created.
     */
    ORDER_CREATED("order.created"),
    /**
     * Inventory reservation completed.
     */
    INVENTORY_RESERVED("inventory.reserved"),
    /**
     * Payment charging completed.
     */
    PAYMENT_CHARGED("payment.charged"),
    /**
     * Shipment creation completed.
     */
    SHIPMENT_CREATED("shipment.created");

    private final String value;

    OrderEvents(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
