package org.jworkflow.security;

import org.jworkflow.events.WorkflowEvent;
import java.util.Objects;

/** Backward-compatible default that captures the immutable event unchanged. */
public enum CaptureAllEventPolicy implements EventCapturePolicy {
    /**
     * Shared stateless instance.
     */
    INSTANCE;
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowEvent filter(WorkflowEvent event) { return Objects.requireNonNull(event, "event"); }
}
