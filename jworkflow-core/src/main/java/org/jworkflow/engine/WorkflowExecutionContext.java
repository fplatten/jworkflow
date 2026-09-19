package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowInstanceId;

import java.util.Objects;
import java.util.Optional;

/**
 * Thread-local command and workflow identity propagated around handler execution. Scopes restore the previous
 * context and must be closed on the same thread that opened them.
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param workflowName workflow definition name
 * @param workflowVersion workflow definition version
 * @param stepName workflow node name for this step
 * @param event event to deliver or inspect
 * @param correlationId identity shared by related commands and events
 * @param businessKey application business identity associated with the workflow
 * @param causationId identity of the command or event that caused this work
 * @param traceId host-provided distributed tracing identity
 */
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

    /**
     * Creates this value from the supplied components.
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param workflowName workflow definition name
     * @param workflowVersion workflow definition version
     * @param stepName workflow node name for this step
     * @param event event to deliver or inspect
     * @param correlationId identity shared by related commands and events
     * @param businessKey application business identity associated with the workflow
     * @param causationId identity of the command or event that caused this work
     * @param traceId host-provided distributed tracing identity
     * @throws NullPointerException if workflowInstanceId, workflowName, stepName is null
     */
    @SuppressWarnings("java:S107") // Record exposes the complete tracing identity by design.
    public WorkflowExecutionContext {
        Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        Objects.requireNonNull(workflowName, "workflowName");
        Objects.requireNonNull(stepName, "stepName");
    }

    /**
     * Returns the context installed on the calling thread, if any.
     * @return the matching value, or an empty optional when absent
     */
    public static Optional<WorkflowExecutionContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Captures the current context, including the absence of context, for later wrapped execution.
     * @return the resulting workflow context snapshot
     */
    public static WorkflowContextSnapshot capture() {
        return new WorkflowContextSnapshot(CURRENT.get());
    }

    static Scope bind(WorkflowExecutionContext context) {
        WorkflowExecutionContext previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(context, "context"));
        return new Scope(previous);
    }

    /**
     * Restores the previous thread-local execution context when closed. Intended for try-with-resources on the
     * installing thread.
     */
    static final class Scope implements AutoCloseable {
        private final WorkflowExecutionContext previous;
        private boolean closed;

        private Scope(WorkflowExecutionContext previous) {
            this.previous = previous;
        }

        /**
         * {@inheritDoc}
         */
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
