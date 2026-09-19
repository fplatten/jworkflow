package org.jworkflow.routing;

import org.jworkflow.application.Command;
import org.jworkflow.events.WorkflowEvent;

import java.util.Objects;

/**
 * Application command carrying a complete workflow event and explicit route scope.
 * @param event event to deliver or inspect
 * @param route explicit event destination identity and delivery mode
 */
public record RouteWorkflowEventCommand(WorkflowEvent event, WorkflowEventRoute route) implements Command {
    /**
     * Creates this value from the supplied components.
     * @param event event to deliver or inspect
     * @param route explicit event destination identity and delivery mode
     * @throws NullPointerException if event, route is null
     */
    public RouteWorkflowEventCommand {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(route, "route");
    }
}
