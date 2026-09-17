package org.jworkflow.security;

import org.jworkflow.events.WorkflowEvent;

/** Applies the application's data-capture policy before an event crosses a durable or observable boundary. */
@FunctionalInterface
public interface EventCapturePolicy {
    WorkflowEvent filter(WorkflowEvent event);
}
