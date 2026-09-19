package org.jworkflow.inbox;

import java.util.Objects;

/**
 * Reports whether acceptance inserted a new row or returned the existing first-arrival inbox message.
 * @param message durable incoming message envelope
 * @param inserted whether the message was newly inserted rather than already stored
 */
public record InboxInsertResult(InboxMessage message, boolean inserted) {
    /**
     * Creates this value from the supplied components.
     * @param message durable incoming message envelope
     * @param inserted whether the message was newly inserted rather than already stored
     * @throws NullPointerException if message is null
     */
    public InboxInsertResult { Objects.requireNonNull(message, "message"); }
}
