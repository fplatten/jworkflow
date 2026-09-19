package org.jworkflow.events;


/**
 * Publisher that deliberately discards every event, used when no external event sink is configured.
 */
public final class NoOpEventPublisher implements EventPublisher {
    /**
     * Shared stateless instance.
     */
    public static final NoOpEventPublisher INSTANCE = new NoOpEventPublisher();

    private NoOpEventPublisher() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void publish(WorkflowEvent event) {
        // Intentionally empty default for embedded use without event listeners.
    }
}
