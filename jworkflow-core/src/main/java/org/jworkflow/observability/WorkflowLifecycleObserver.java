package org.jworkflow.observability;

@FunctionalInterface
public interface WorkflowLifecycleObserver {
    void observe(WorkflowLifecycleEvent event);
}
