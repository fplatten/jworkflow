package org.jworkflow.security;

import org.jworkflow.events.WorkflowEvent;
import java.util.Objects;

/** Enforces the policy contract and makes repeated filtering safe at layered boundaries. */
public final class SafeEventCapturePolicy {
    private SafeEventCapturePolicy() {}
    /**
     * Invokes the policy and rejects a null policy, input or filtered result. Policy failures are propagated
     * before persistence.
     * @param policy event capture/redaction policy
     * @param event event to deliver or inspect
     * @return the resulting workflow event
     * @throws NullPointerException if policy, event is null
     */
    public static WorkflowEvent filter(EventCapturePolicy policy, WorkflowEvent event) {
        WorkflowEvent filtered = Objects.requireNonNull(policy, "policy").filter(Objects.requireNonNull(event, "event"));
        return Objects.requireNonNull(filtered, "EventCapturePolicy must not return null");
    }
}
