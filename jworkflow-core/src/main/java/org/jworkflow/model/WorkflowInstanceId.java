package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Objects;
import java.util.UUID;

public record WorkflowInstanceId(UUID value) {
    public WorkflowInstanceId {
        Objects.requireNonNull(value, "value");
    }

    public static WorkflowInstanceId random() {
        return new WorkflowInstanceId(UUID.randomUUID());
    }

    public static WorkflowInstanceId fromString(String value) {
        return new WorkflowInstanceId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
