package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;

import java.util.Map;
import java.util.Objects;

public final class WorkflowStepContext {
    private final Map<String, Object> listeners;

    WorkflowStepContext(Map<String, Object> listeners) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }

    public Object listener(String listenerId) {
        if (listenerId == null || listenerId.isBlank()) {
            throw new IllegalArgumentException("listenerId is required");
        }
        Object listener = listeners.get(listenerId);
        if (listener == null) {
            throw new WorkflowInfrastructureException("No listener registered for " + listenerId, null);
        }
        return listener;
    }

    public WorkflowEvent event() {
        return WorkflowExecutionContext.current()
                .map(WorkflowExecutionContext::event)
                .orElse(null);
    }
}
