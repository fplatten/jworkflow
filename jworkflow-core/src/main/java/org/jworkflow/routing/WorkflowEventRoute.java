package org.jworkflow.routing;

import org.jworkflow.events.EventName;
import org.jworkflow.model.WorkflowInstanceId;

/** Explicit, immutable routing intent. Tenant is reserved for hosts that implement tenant-aware persistence. */
public record WorkflowEventRoute(
        WorkflowInstanceId workflowInstanceId,
        String workflowKey,
        String businessKey,
        String correlationId,
        String tenantId,
        EventName expectedEvent,
        WorkflowRoutingMode mode
) {
    public WorkflowEventRoute {
        if (expectedEvent == null) throw new IllegalArgumentException("expectedEvent is required");
        mode = mode == null ? WorkflowRoutingMode.SINGLE : mode;
        if (workflowInstanceId != null && mode == WorkflowRoutingMode.FAN_OUT) {
            throw new IllegalArgumentException("Exact instance routing cannot fan out");
        }
        if (workflowInstanceId == null) {
            requireText(workflowKey, "workflowKey");
            if (blank(correlationId) && blank(businessKey)) {
                throw new IllegalArgumentException("Scoped routing requires correlationId or businessKey");
            }
        }
    }

    public static WorkflowEventRoute exact(WorkflowInstanceId id, EventName event) {
        return new WorkflowEventRoute(id, null, null, null, null, event, WorkflowRoutingMode.SINGLE);
    }

    public static WorkflowEventRoute correlated(String workflowKey, String correlationId, EventName event) {
        return new WorkflowEventRoute(null, workflowKey, null, correlationId, null, event, WorkflowRoutingMode.SINGLE);
    }

    public static WorkflowEventRoute businessKey(String workflowKey, String businessKey, EventName event) {
        return new WorkflowEventRoute(null, workflowKey, businessKey, null, null, event, WorkflowRoutingMode.SINGLE);
    }

    public WorkflowEventRoute fanOut() {
        return new WorkflowEventRoute(null, workflowKey, businessKey, correlationId, tenantId, expectedEvent,
                WorkflowRoutingMode.FAN_OUT);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireText(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
    }
}
