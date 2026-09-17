package org.jworkflow.inbox;

import org.jworkflow.routing.RouteWorkflowEventCommand;

import java.util.List;

@FunctionalInterface
public interface InboxRoutingTranslator {
    List<RouteWorkflowEventCommand> translate(InboxMessage message);

    default InboxEventTranslator asCommandTranslator() {
        return this::translate;
    }
}
