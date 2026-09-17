package org.jworkflow.example.infrastructure.workflow;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.example.application.command.ChargePaymentCommand;
import org.jworkflow.example.application.port.OrderCommandDispatcher;

import java.util.Objects;

/** Inbound workflow boundary translating an integration event into an application command. */
public final class PaymentChargeListener {
    private final OrderCommandDispatcher commandDispatcher;
    private final OrderWorkflowEventMapper eventMapper = new OrderWorkflowEventMapper();

    public PaymentChargeListener(OrderCommandDispatcher commandDispatcher) {
        this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher");
    }

    public void onEvent(WorkflowEvent event) {
        commandDispatcher.dispatch(new ChargePaymentCommand(eventMapper.toOrder(event)));
    }
}
