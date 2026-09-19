package org.jworkflow.observability;

/**
 * Observer singleton that discards lifecycle events.
 */
public enum NoOpWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    /**
     * Shared stateless instance.
     */
    INSTANCE;

    /**
     * {@inheritDoc}
     */
    @Override
    public void observe(WorkflowLifecycleEvent event) {
        // Deliberately discard lifecycle events when observability is not configured.
    }
}
