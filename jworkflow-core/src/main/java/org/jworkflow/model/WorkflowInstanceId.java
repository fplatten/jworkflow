package org.jworkflow.model;


import java.util.Objects;
import java.util.UUID;

/**
 * Non-null workflow instance identity, normally created from a random UUID.
 * @param value UUID identifying the workflow instance
 */
public record WorkflowInstanceId(UUID value) {
    /**
     * Creates this value from the supplied components.
     * @param value UUID identifying the workflow instance
     * @throws NullPointerException if value is null
     */
    public WorkflowInstanceId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a workflow instance identity from a random UUID.
     * @return the resulting workflow instance id
     */
    public static WorkflowInstanceId random() {
        return new WorkflowInstanceId(UUID.randomUUID());
    }

    /**
     * Parses a UUID string into a workflow instance identity.
     * @param value UUID string to parse
     * @return the resulting workflow instance id
     */
    public static WorkflowInstanceId fromString(String value) {
        return new WorkflowInstanceId(UUID.fromString(value));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return value.toString();
    }
}
