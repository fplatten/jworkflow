package org.jworkflow.routing;

import org.jworkflow.events.WorkflowEvent;

/**
 * Explicit durable-event routing boundary. Implementations must not silently choose one instance from ambiguous
 * candidates or turn single-target delivery into fan-out.
 */
public interface WorkflowEventRouter {
    /**
     * Routes an event to eligible existing workflows under the explicit scope and ambiguity policy.
     * @param event event to deliver or inspect
     * @param route explicit event destination identity and delivery mode
     * @return the resulting workflow routing result
     */
    WorkflowRoutingResult route(WorkflowEvent event, WorkflowEventRoute route);
}
