package org.jworkflow.events;


public final class NoOpEventPublisher implements EventPublisher {
    public static final NoOpEventPublisher INSTANCE = new NoOpEventPublisher();

    private NoOpEventPublisher() {
    }

    @Override
    public void publish(WorkflowEvent event) {
        // Intentionally empty default for embedded use without event listeners.
    }
}
