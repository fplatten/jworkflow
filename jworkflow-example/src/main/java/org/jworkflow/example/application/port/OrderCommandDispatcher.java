package org.jworkflow.example.application.port;

import org.jworkflow.example.application.command.OrderCommand;

@FunctionalInterface
public interface OrderCommandDispatcher {
    void dispatch(OrderCommand command);
}
