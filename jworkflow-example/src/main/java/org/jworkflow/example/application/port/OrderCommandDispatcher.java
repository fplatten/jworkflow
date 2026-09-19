package org.jworkflow.example.application.port;

import org.jworkflow.example.application.command.OrderCommand;

/**
 * Application command boundary used by the example's workflow adapter.
 */
@FunctionalInterface
public interface OrderCommandDispatcher {
    /**
     * Dispatches a typed order command to application behavior and publishes its resulting workflow event.
     * @param command command to validate and execute
     */
    void dispatch(OrderCommand command);
}
