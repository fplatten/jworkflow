package org.jworkflow.observability;

public enum NoOpWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    INSTANCE;

    @Override
    public void observe(WorkflowLifecycleEvent event) {
        // Deliberately discard lifecycle events when observability is not configured.
    }
}
