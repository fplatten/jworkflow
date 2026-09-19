package org.jworkflow.example.infrastructure.workflow;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.example.application.command.ReserveInventoryCommand;
import org.jworkflow.example.application.port.OrderCommandDispatcher;

import java.util.Objects;

/** Inbound workflow boundary translating an integration event into an application command. */
public final class InventoryReservationListener {
    private final OrderCommandDispatcher commandDispatcher;
    private final OrderWorkflowEventMapper eventMapper = new OrderWorkflowEventMapper();

    /**
     * Constructs InventoryReservationListener with the supplied collaborators and configuration.
     * @param commandDispatcher application command boundary
     * @throws NullPointerException if commandDispatcher is null
     */
    public InventoryReservationListener(OrderCommandDispatcher commandDispatcher) {
        this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher");
    }

    /**
     * Maps the event to an order and dispatches its inventory reservation command.
     * @param event event to deliver or inspect
     */
    public void onEvent(WorkflowEvent event) {
        commandDispatcher.dispatch(new ReserveInventoryCommand(eventMapper.toOrder(event)));
    }
}
