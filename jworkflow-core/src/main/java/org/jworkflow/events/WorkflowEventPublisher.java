package org.jworkflow.events;

import org.jworkflow.engine.WorkflowEngine;

import java.util.Objects;

/**
 * Stateless publisher that forwards events to the process-wide engine installed through
 * WorkflowEngine.setInstance. It owns no engine resources.
 */
public final class WorkflowEventPublisher implements EventPublisher {
    /**
     * Shared stateless instance.
     */
    public static final WorkflowEventPublisher INSTANCE = new WorkflowEventPublisher();

    /**
     * Constructs WorkflowEventPublisher with its default configuration.
     */
    public WorkflowEventPublisher() {
        // Public for dependency-injection containers; the publisher has no instance state.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publish(WorkflowEvent event) {
        WorkflowEngine.instance().publish(Objects.requireNonNull(event, "event"));
    }
}
