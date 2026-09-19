package org.jworkflow.routing;

import org.jworkflow.events.EventName;
import org.jworkflow.model.WorkflowInstanceId;

/**
 * Explicit, immutable routing intent. Tenant is reserved for hosts that implement tenant-aware persistence.
 *
 * <p>Exact instance identity takes precedence over scoped workflow/correlation or workflow/business identity.
 * Built-in JDBC routing rejects non-empty tenant IDs because tenant-aware persistence is not implemented. Fan-out
 * must be explicitly selected; ambiguous single-target delivery fails.</p>
 *
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param workflowKey registered workflow name used to resolve a definition
 * @param businessKey application business identity associated with the workflow
 * @param correlationId identity shared by related commands and events
 * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
 * @param expectedEvent event name accepted by the pending wait
 * @param mode single-target or explicitly requested fan-out delivery
 */
public record WorkflowEventRoute(
        WorkflowInstanceId workflowInstanceId,
        String workflowKey,
        String businessKey,
        String correlationId,
        String tenantId,
        EventName expectedEvent,
        WorkflowRoutingMode mode
) {
    /**
     * Creates this value from the supplied components.
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @param correlationId identity shared by related commands and events
     * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
     * @param expectedEvent event name accepted by the pending wait
     * @param mode single-target or explicitly requested fan-out delivery
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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

    /**
     * Creates a single-instance route requiring the supplied expected event.
     * @param id identity of the value to look up or update
     * @param event event to deliver or inspect
     * @return the resulting workflow event route
     */
    public static WorkflowEventRoute exact(WorkflowInstanceId id, EventName event) {
        return new WorkflowEventRoute(id, null, null, null, null, event, WorkflowRoutingMode.SINGLE);
    }

    /**
     * Creates a single-target route scoped by workflow key and correlation identity.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param correlationId identity shared by related commands and events
     * @param event event to deliver or inspect
     * @return the resulting workflow event route
     */
    public static WorkflowEventRoute correlated(String workflowKey, String correlationId, EventName event) {
        return new WorkflowEventRoute(null, workflowKey, null, correlationId, null, event, WorkflowRoutingMode.SINGLE);
    }

    /**
     * Creates a single-target route scoped by workflow key and business identity.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @param event event to deliver or inspect
     * @return the resulting workflow event route
     */
    public static WorkflowEventRoute businessKey(String workflowKey, String businessKey, EventName event) {
        return new WorkflowEventRoute(null, workflowKey, businessKey, null, null, event, WorkflowRoutingMode.SINGLE);
    }

    /**
     * Copies this route in fan-out mode, clearing any exact instance target and retaining its scope.
     * @return the resulting workflow event route
     */
    public WorkflowEventRoute fanOut() {
        return new WorkflowEventRoute(null, workflowKey, businessKey, correlationId, tenantId, expectedEvent,
                WorkflowRoutingMode.FAN_OUT);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireText(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
    }
}
