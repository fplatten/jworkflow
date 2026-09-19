package org.jworkflow.observability;

/**
 * Best-effort lifecycle observation boundary. JDBC built-ins defer successful notifications until the outermost
 * adapter commit and cleanup; rollback suppresses them. Notifications are not a durable delivery mechanism.
 */
@FunctionalInterface
public interface WorkflowLifecycleObserver {
    /**
     * Receives a best-effort lifecycle observation; it must not be treated as durable external delivery.
     * @param event event to deliver or inspect
     */
    void observe(WorkflowLifecycleEvent event);
}
