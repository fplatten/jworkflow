package org.jworkflow.outbox;
/**
 * Application transport for sending a claimed outbox message. Called outside the claim/recording transaction. A
 * successful send can be followed by a recording failure, so consumers must deduplicate by stable message
 * identity.
 */
@FunctionalInterface public interface DestinationPublisher {

    /**
     * Sends one message outside the adapter transaction. Success means the transport accepted it, not that
     * database
     *  publication recording has committed.
     * @param message durable outgoing publication envelope
     */
    void publish(OutboxMessage message);}
