package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowInstanceId;

import java.util.Objects;
import java.util.Optional;

public final class WorkflowExecutionContext {
    private static final ThreadLocal<WorkflowExecutionContext> CURRENT = new ThreadLocal<>();

    private final WorkflowInstanceId workflowInstanceId;
    private final String workflowName;
    private final String workflowVersion;
    private final String stepName;
    private final WorkflowEvent event;
    private final String correlationId;
    private final String businessKey;
    private final String causationId;
    private final String traceId;

    public WorkflowExecutionContext(
            WorkflowInstanceId workflowInstanceId,
            String workflowName,
            String workflowVersion,
            String stepName,
            WorkflowEvent event,
            String correlationId,
            String businessKey,
            String causationId,
            String traceId
    ) {
        this.workflowInstanceId = Objects.requireNonNull(workflowInstanceId, "workflowInstanceId");
        this.workflowName = Objects.requireNonNull(workflowName, "workflowName");
        this.workflowVersion = workflowVersion;
        this.stepName = Objects.requireNonNull(stepName, "stepName");
        this.event = event;
        this.correlationId = correlationId;
        this.businessKey = businessKey;
        this.causationId = causationId;
        this.traceId = traceId;
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

    public WorkflowInstanceId workflowInstanceId() {
        return workflowInstanceId;
    }

    public String workflowName() {
        return workflowName;
    }

    public String workflowVersion() {
        return workflowVersion;
    }

    public String stepName() {
        return stepName;
    }

    public WorkflowEvent event() {
        return event;
    }

    public String correlationId() {
        return correlationId;
    }

    public String businessKey() {
        return businessKey;
    }

    public String causationId() {
        return causationId;
    }

    public String traceId() {
        return traceId;
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
