package org.jworkflow.example;

import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;

public final class OrderFulfillmentExampleTest {
    public static void main(String[] args) throws Exception {
        WorkflowSnapshot snapshot = OrderFulfillmentExample.runSuccessfulOrder();
        if (snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected order workflow to complete, got " + snapshot.status());
        }
        if (!"completed".equals(snapshot.state())) {
            throw new AssertionError("Expected order workflow to end at completed, got " + snapshot.state());
        }
        if (!Boolean.TRUE.equals(snapshot.variables().get("inventoryReserved"))) {
            throw new AssertionError("Expected inventory reservation result variable");
        }
        if (!Boolean.TRUE.equals(snapshot.variables().get("paymentCharged"))) {
            throw new AssertionError("Expected payment charge result variable");
        }
        if (!Boolean.TRUE.equals(snapshot.variables().get("shipmentCreated"))) {
            throw new AssertionError("Expected shipment creation result variable");
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
