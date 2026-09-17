package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public interface EventSubscription extends AutoCloseable {
    void unsubscribe();

    @Override
    default void close() {
        unsubscribe();
    }
}
