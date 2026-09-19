package org.jworkflow.engine;

import org.jworkflow.model.*;

import java.util.Objects;

/**
 * Delivers a named signal and its immutable message envelope to one workflow instance.
 * @param instanceId workflow instance identity
 * @param signal triggering signal and its original message envelope
 * @param metadata command identity, audit context and optional replay key
 */
public record SignalWorkflowCommand(
        WorkflowInstanceId instanceId,
        WorkflowSignal signal,
        WorkflowCommandMetadata metadata
) implements org.jworkflow.application.Command {
    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param signal triggering signal and its original message envelope
     * @param metadata command identity, audit context and optional replay key
     * @throws NullPointerException if instanceId, signal is null
     */
    public SignalWorkflowCommand {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(signal, "signal");
        metadata = metadata == null
                ? WorkflowCommandMetadata.defaults(null, null, instanceId, signal.businessKey())
                : metadata;
    }
}
