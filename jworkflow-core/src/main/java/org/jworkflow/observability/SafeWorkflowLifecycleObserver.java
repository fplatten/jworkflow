package org.jworkflow.observability;

import java.util.Objects;

public final class SafeWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    private final WorkflowLifecycleObserver delegate;
    private final LifecycleObservationErrorHandler errors;

    public SafeWorkflowLifecycleObserver(
            WorkflowLifecycleObserver delegate,
            LifecycleObservationErrorHandler errors
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    public static WorkflowLifecycleObserver isolate(WorkflowLifecycleObserver observer) {
        System.Logger logger = System.getLogger("org.jworkflow.observability");
        return new SafeWorkflowLifecycleObserver(observer, (event, failure) -> logger.log(
                System.Logger.Level.WARNING,
                "Workflow lifecycle observer failed for " + event.type(), failure));
    }

    @Override
    public void observe(WorkflowLifecycleEvent event) {
        try {
            delegate.observe(Objects.requireNonNull(event, "event"));
        } catch (RuntimeException failure) {
            try {
                errors.onError(event, failure);
            } catch (RuntimeException ignored) {
                // Observability is best-effort and must never affect workflow execution.
            }
        }
    }
}
