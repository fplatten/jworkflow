package org.jworkflow.security;

import org.jworkflow.events.WorkflowEvent;
import java.util.Objects;

/** Enforces the policy contract and makes repeated filtering safe at layered boundaries. */
public final class SafeEventCapturePolicy {
    private SafeEventCapturePolicy() {}
    public static WorkflowEvent filter(EventCapturePolicy policy, WorkflowEvent event) {
        WorkflowEvent filtered = Objects.requireNonNull(policy, "policy").filter(Objects.requireNonNull(event, "event"));
        return Objects.requireNonNull(filtered, "EventCapturePolicy must not return null");
    }
}
