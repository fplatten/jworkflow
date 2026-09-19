package org.jworkflow.events;

import java.util.Objects;

/**
 * Static convenience entry point for publishing through the installed default workflow engine.
 */
public final class Events {
    private Events() {
    }

    /**
     * Publishes through the process-wide installed workflow engine; fails if no engine is installed.
     * @param event event to deliver or inspect
     * @throws NullPointerException if event is null
     */
    public static void publish(WorkflowEvent event) {
        WorkflowEventPublisher.INSTANCE.publish(Objects.requireNonNull(event, "event"));
    }
}
