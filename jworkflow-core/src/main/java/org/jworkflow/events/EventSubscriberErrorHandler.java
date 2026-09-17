package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

@FunctionalInterface
public interface EventSubscriberErrorHandler {
    void handle(EventDeliveryFailure failure);

    static EventSubscriberErrorHandler rethrowing() {
        return failure -> {
            if (failure.error() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(failure.error());
        };
    }

    static EventSubscriberErrorHandler ignoring() {
        return failure -> {
        };
    }
}
