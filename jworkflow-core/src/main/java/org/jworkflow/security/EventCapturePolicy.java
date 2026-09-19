package org.jworkflow.security;

import org.jworkflow.events.WorkflowEvent;

/**
 * Applies the application's data-capture policy before an event crosses a durable or observable boundary.
 *
 * <p>Hosts must configure capture deliberately: the default preserves payloads. Filtering does not replace
 * authorization or transport encryption. Policies must preserve event identity and routing metadata required by
 * the runtime.</p>
 */
@FunctionalInterface
public interface EventCapturePolicy {
    /**
     * Returns the event permitted to cross persistence and observation boundaries. Implementations must return a
     * non-null envelope and should preserve routing/identity metadata.
     * @param event event to deliver or inspect
     * @return the event permitted to cross persistence and observation boundaries
     */
    WorkflowEvent filter(WorkflowEvent event);
}
