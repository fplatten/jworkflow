package org.jworkflow.observability;

import java.util.Objects;

public final class SystemLoggerWorkflowLifecycleObserver implements WorkflowLifecycleObserver {
    private final System.Logger logger;

    public SystemLoggerWorkflowLifecycleObserver() {
        this(System.getLogger("org.jworkflow.lifecycle"));
    }

    public SystemLoggerWorkflowLifecycleObserver(System.Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void observe(WorkflowLifecycleEvent event) {
        logger.log(System.Logger.Level.INFO, () -> "event=" + event.type()
                + " workflowInstanceId=" + value(event.workflowInstanceId())
                + " workflowKey=" + value(event.workflowKey())
                + " workflowVersion=" + value(event.workflowVersion())
                + " state=" + value(event.state())
                + " step=" + value(event.step())
                + " correlationId=" + value(event.correlationId())
                + " causationId=" + value(event.causationId())
                + " traceId=" + value(event.traceId())
                + " attributes=" + event.attributes());
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString().replace('\n', '_').replace('\r', '_');
    }
}
