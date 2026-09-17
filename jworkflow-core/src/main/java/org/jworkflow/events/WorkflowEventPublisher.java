package org.jworkflow.events;

import org.jworkflow.engine.WorkflowEngine;

import java.util.Objects;

public final class WorkflowEventPublisher implements EventPublisher {
    public static final WorkflowEventPublisher INSTANCE = new WorkflowEventPublisher();

    public WorkflowEventPublisher() {
    }

    @Override
    public void publish(WorkflowEvent event) {
        WorkflowEngine.instance().publish(Objects.requireNonNull(event, "event"));
    }
}
