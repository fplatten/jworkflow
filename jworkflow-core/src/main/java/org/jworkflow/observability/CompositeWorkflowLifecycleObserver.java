package org.jworkflow.observability;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class CompositeWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    private final List<WorkflowLifecycleObserver> observers;

    private CompositeWorkflowLifecycleObserver(List<WorkflowLifecycleObserver> observers) {
        this.observers = observers.stream().map(SafeWorkflowLifecycleObserver::isolate).toList();
    }

    public static WorkflowLifecycleObserver of(WorkflowLifecycleObserver... observers) {
        List<WorkflowLifecycleObserver> safe = Arrays.stream(observers == null ? new WorkflowLifecycleObserver[0] : observers)
                .map(observer -> Objects.requireNonNull(observer, "observer"))
                .toList();
        if (safe.isEmpty()) return NoOpWorkflowLifecycleObserver.INSTANCE;
        if (safe.size() == 1) return safe.get(0);
        return new CompositeWorkflowLifecycleObserver(safe);
    }

    @Override
    public void observe(WorkflowLifecycleEvent event) {
        observers.forEach(observer -> observer.observe(event));
    }
}
