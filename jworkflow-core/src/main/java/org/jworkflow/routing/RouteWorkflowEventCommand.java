package org.jworkflow.routing;

import org.jworkflow.application.Command;
import org.jworkflow.events.WorkflowEvent;

import java.util.Objects;

public record RouteWorkflowEventCommand(WorkflowEvent event, WorkflowEventRoute route) implements Command {
    public RouteWorkflowEventCommand {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(route, "route");
    }
}
