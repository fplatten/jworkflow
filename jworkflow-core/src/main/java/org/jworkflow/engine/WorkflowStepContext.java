package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;

import java.util.Map;
import java.util.Objects;

/**
 * Listener-facing access to registered infrastructure objects and the current thread-local workflow event. Only
 * registered listener IDs can be resolved; no arbitrary DSL code is evaluated here.
 */
public final class WorkflowStepContext {
    private final Map<String, Object> listeners;

    WorkflowStepContext(Map<String, Object> listeners) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }

    /**
     * Resolves a registered listener ID, failing clearly if the ID is blank or unregistered.
     * @param listenerId registered infrastructure listener identity
     * @return the resulting object
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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

    /**
     * Returns the event in the current thread-local workflow context, or null outside an event-bearing invocation.
     * @return the event in the current thread-local workflow context, or null outside an event-bearing invocation
     */
    public WorkflowEvent event() {
        return WorkflowExecutionContext.current()
                .map(WorkflowExecutionContext::event)
                .orElse(null);
    }
}
