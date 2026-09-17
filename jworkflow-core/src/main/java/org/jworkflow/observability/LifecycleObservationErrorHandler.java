package org.jworkflow.observability;

@FunctionalInterface
public interface LifecycleObservationErrorHandler {
    void onError(WorkflowLifecycleEvent event, RuntimeException failure);
}
