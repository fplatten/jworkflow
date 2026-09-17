package org.jworkflow.events;

import java.util.Objects;

public final class Events {
    private Events() {
    }

    public static void publish(WorkflowEvent event) {
        WorkflowEventPublisher.INSTANCE.publish(Objects.requireNonNull(event, "event"));
    }
}
