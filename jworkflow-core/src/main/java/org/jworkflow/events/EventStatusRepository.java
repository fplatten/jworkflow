package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.List;
import java.util.UUID;

public interface EventStatusRepository {
    void append(EventStatusAttempt attempt);

    List<EventStatusAttempt> findAttempts(UUID eventId);
}
