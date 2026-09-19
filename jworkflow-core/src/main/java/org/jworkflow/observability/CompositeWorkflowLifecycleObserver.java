package org.jworkflow.observability;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Dispatches each lifecycle observation to an ordered collection of observers while isolating observer failures
 * through safe wrappers.
 */
public final class CompositeWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    private final List<WorkflowLifecycleObserver> observers;

    private CompositeWorkflowLifecycleObserver(List<WorkflowLifecycleObserver> observers) {
        this.observers = observers.stream().map(SafeWorkflowLifecycleObserver::isolate).toList();
    }

    /**
     * Creates ordered observation fan-out; an empty list returns a no-op and one observer is returned directly.
     * @param observers lifecycle observers in dispatch order
     * @return the resulting workflow lifecycle observer
     */
    public static WorkflowLifecycleObserver of(WorkflowLifecycleObserver... observers) {
        List<WorkflowLifecycleObserver> safe = Arrays.stream(observers == null ? new WorkflowLifecycleObserver[0] : observers)
                .map(observer -> Objects.requireNonNull(observer, "observer"))
                .toList();
        if (safe.isEmpty()) return NoOpWorkflowLifecycleObserver.INSTANCE;
        if (safe.size() == 1) return safe.get(0);
        return new CompositeWorkflowLifecycleObserver(safe);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void observe(WorkflowLifecycleEvent event) {
        observers.forEach(observer -> observer.observe(event));
    }
}
