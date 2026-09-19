package org.jworkflow.engine;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Captures the current thread-local workflow execution context for later Runnable or Callable execution. Wrapped
 * work restores the receiving thread's previous context when it finishes.
 */
public final class WorkflowContextSnapshot {
    private final WorkflowExecutionContext context;

    WorkflowContextSnapshot(WorkflowExecutionContext context) {
        this.context = context;
    }

    /**
     * Wraps work to install the captured context for its duration and restore the receiving thread's previous
     * context afterward.
     * @param runnable work to run under the captured context
     * @return a task that installs and restores the captured context
     * @throws NullPointerException if runnable is null
     */
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

    /**
     * Wraps work to install the captured context for its duration and restore the receiving thread's previous
     * context afterward.
     * @param <T> the result type
     * @param callable value-returning work to run under the captured context
     * @return a task that installs and restores the captured context
     * @throws NullPointerException if callable is null
     */
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
