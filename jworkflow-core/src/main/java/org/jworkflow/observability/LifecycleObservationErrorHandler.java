package org.jworkflow.observability;

/**
 * Diagnostic callback for an observer failure. It must not change workflow outcomes or leak sensitive event data.
 */
@FunctionalInterface
public interface LifecycleObservationErrorHandler {
    /**
     * Reports an isolated observer failure without changing the workflow result.
     * @param event event to deliver or inspect
     * @param failure original failure for diagnostics; avoid exposing secrets in logs
     */
    void onError(WorkflowLifecycleEvent event, RuntimeException failure);
}
