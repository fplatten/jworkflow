package org.jworkflow.model;


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
