package org.jworkflow.engine;

import java.util.Objects;
import java.util.concurrent.Callable;

public final class WorkflowContextSnapshot {
    private final WorkflowExecutionContext context;

    WorkflowContextSnapshot(WorkflowExecutionContext context) {
        this.context = context;
    }

    public Runnable wrap(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        return () -> {
            if (context == null) {
                runnable.run();
                return;
            }
            try (WorkflowExecutionContext.Scope ignored = WorkflowExecutionContext.bind(context)) {
                runnable.run();
            }
        };
    }

    public <T> Callable<T> wrap(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        return () -> {
            if (context == null) {
                return callable.call();
            }
            try (WorkflowExecutionContext.Scope ignored = WorkflowExecutionContext.bind(context)) {
                return callable.call();
            }
        };
    }
}
