package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public final class NoOpEventPublisher implements EventPublisher {
    public static final NoOpEventPublisher INSTANCE = new NoOpEventPublisher();

    private NoOpEventPublisher() {
    }

    @Override
    public void publish(WorkflowEvent event) {
        // Intentionally empty default for embedded use without event listeners.
    }
}
