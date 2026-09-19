package org.jworkflow.events;


/**
 * Closeable registration handle. Closing removes the subscription; it does not close the event bus or subscriber.
 */
public interface EventSubscription extends AutoCloseable {
    /**
     * Removes this registration without closing the publisher or subscriber.
     */
    void unsubscribe();

    /**
     * {@inheritDoc}
     */
    @Override
    default void close() {
        unsubscribe();
    }
}
