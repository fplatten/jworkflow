package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public interface EventBus extends EventPublisher {
    EventSubscription subscribe(String topic, EventSubscriber subscriber);

    EventSubscription subscribe(EventListener listener);

    void publish(String topic, WorkflowEvent event);

    @Override
    default void publish(WorkflowEvent event) {
        publish(event.eventName().value(), event);
    }
}
