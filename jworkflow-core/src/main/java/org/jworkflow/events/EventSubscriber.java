package org.jworkflow.events;


@FunctionalInterface
public interface EventSubscriber {
    void onEvent(WorkflowEvent event);
}
