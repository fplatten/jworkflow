package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public interface EventPublisher {
    void publish(WorkflowEvent event);

    default void publish(IntegrationEvent event) {
        publish(java.util.Objects.requireNonNull(event, "event").toWorkflowEvent());
    }
}
