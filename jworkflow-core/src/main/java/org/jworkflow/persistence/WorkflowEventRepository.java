package org.jworkflow.persistence;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface WorkflowEventRepository {
    void append(WorkflowEvent event);

    Optional<WorkflowEvent> find(UUID eventId);

    List<WorkflowEvent> findByWorkflowInstance(WorkflowInstanceId instanceId);

    default List<WorkflowEvent> findAllAfter(java.time.Instant afterExclusive, int limit) { return List.of(); }
}
