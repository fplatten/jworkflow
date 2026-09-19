package org.jworkflow.observability;

import java.util.Objects;

/**
 * Isolates lifecycle observer exceptions so observational failures cannot control workflow state.
 */
public final class SafeWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    private final WorkflowLifecycleObserver delegate;
    private final LifecycleObservationErrorHandler errors;

    /**
     * Constructs SafeWorkflowLifecycleObserver with the supplied collaborators and configuration.
     * @param delegate host executor or observer being adapted; ownership stays with the caller
     * @param errors validation error details
     * @throws NullPointerException if delegate, errors is null
     */
    public SafeWorkflowLifecycleObserver(
            WorkflowLifecycleObserver delegate,
            LifecycleObservationErrorHandler errors
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /**
     * Returns an observer that prevents delegate exceptions from escaping into workflow execution.
     * @param observer best-effort lifecycle observer
     * @return an observer that prevents delegate exceptions from escaping into workflow execution
     */
    public static WorkflowLifecycleObserver isolate(WorkflowLifecycleObserver observer) {
        System.Logger logger = System.getLogger("org.jworkflow.observability");
        return new SafeWorkflowLifecycleObserver(observer, (event, failure) -> logger.log(
                System.Logger.Level.WARNING,
                "Workflow lifecycle observer failed for " + event.type(), failure));
    }

    /**
     * {@inheritDoc}
     */
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
