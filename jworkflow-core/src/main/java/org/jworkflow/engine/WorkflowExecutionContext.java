package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowInstanceId;

import java.util.Objects;
import java.util.Optional;

public record WorkflowExecutionContext(
        WorkflowInstanceId workflowInstanceId,
        String workflowName,
        String workflowVersion,
        String stepName,
        WorkflowEvent event,
        String correlationId,
        String businessKey,
        String causationId,
        String traceId) {
    private static final ThreadLocal<WorkflowExecutionContext> CURRENT = new ThreadLocal<>();

    @SuppressWarnings("java:S107") // Record exposes the complete tracing identity by design.
    public WorkflowExecutionContext {
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(workflowName, "workflowName");
        Objects.requireNonNull(stepName, "stepName");
    }

    public static Optional<WorkflowExecutionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static WorkflowContextSnapshot capture() {
        return new WorkflowContextSnapshot(CURRENT.get());
    }

    static Scope bind(WorkflowExecutionContext context) {
        WorkflowExecutionContext previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(context, "context"));
        return new Scope(previous);
    }

    static final class Scope implements AutoCloseable {
        private final WorkflowExecutionContext previous;
        private boolean closed;

        private Scope(WorkflowExecutionContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
