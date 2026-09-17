workflow("order-fulfillment") {
    version "1.0.0"
    correlateBy "orderId"

    start when: "order.created"

    step("reserveInventory") {
        on "order.created"
        retry maxAttempts: 3, backoff: "PT30S"

        run { event, context ->
            context.listener("inventoryReservationListener").onEvent(event)
        }

        then waitFor "inventory.reserved", goTo: "chargePayment"
        onFailure goTo: "inventoryFailed"
    }

    step("chargePayment") {
        on "inventory.reserved"
        timeout "PT2M"
        sla "PT5M", onBreach: "paymentSlaBreached"

        run { event, context ->
            context.listener("paymentChargeListener").onEvent(event)
        }

        then waitFor "payment.charged", goTo: "createShipment"
        onFailure goTo: "paymentFailed"
    }

    step("createShipment") {
        on "payment.charged"

        run { event, context ->
            context.listener("shipmentCreationListener").onEvent(event)
        }

        then waitFor "shipment.created", goTo: "completed"
        onFailure goTo: "shipmentTimeout"
    }

    waitFor("waitForShipment") {
        event "shipment.created"
        correlateBy "orderId"
        then goTo: "completed"
        timeout "P2D", goTo: "shipmentTimeout"
    }

    end("completed")
    end("inventoryFailed")
    end("paymentFailed")
    end("shipmentTimeout")
}
