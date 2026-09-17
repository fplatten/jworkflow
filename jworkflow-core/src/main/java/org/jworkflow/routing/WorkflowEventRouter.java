package org.jworkflow.routing;

import org.jworkflow.events.WorkflowEvent;

public interface WorkflowEventRouter {
    WorkflowRoutingResult route(WorkflowEvent event, WorkflowEventRoute route);
}
