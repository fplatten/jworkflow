package org.jworkflow.inbox;

import org.jworkflow.routing.RouteWorkflowEventCommand;

import java.util.List;

/**
 * Converts a durable inbox message into an explicit event-route command while preserving the complete event
 * message and metadata.
 */
@FunctionalInterface
public interface InboxRoutingTranslator {
    /**
     * Returns explicit route commands preserving the accepted event envelope.
     * @param message durable incoming message envelope
     * @return the matching values in the order defined by this operation
     */
    List<RouteWorkflowEventCommand> translate(InboxMessage message);

    /**
     * Adapts route translation to the generic inbox command list boundary.
     * @return the resulting inbox event translator
     */
    default InboxEventTranslator asCommandTranslator() {
        return message -> List.copyOf(translate(message));
    }
}
