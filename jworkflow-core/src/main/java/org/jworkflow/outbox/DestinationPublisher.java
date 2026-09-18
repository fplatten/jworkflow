package org.jworkflow.outbox;
@FunctionalInterface public interface DestinationPublisher {void publish(OutboxMessage message);}
