package org.jworkflow.outbox;
import org.jworkflow.events.WorkflowEvent;
    import org.jworkflow.persistence.*;
    import java.util.*;
/**
 * Creates outbox rows from captured workflow events and configured routes. Enqueue in the workflow transaction to
 * make publication intent atomic with state changes.
 */
public final class OutboxEnqueueService {private final OutboxRepository repository;
    private final OutboxRoutingService routing;
    /**
     * Constructs OutboxEnqueueService with the supplied collaborators and configuration.
     * @param repository repository used by this service
     * @param routing the event-to-publication routing service
     * @throws NullPointerException if repository, routing is null
     */
    public OutboxEnqueueService(OutboxRepository repository,OutboxRoutingService routing){this.repository=Objects.requireNonNull(repository);
    this.routing=Objects.requireNonNull(routing);
}

    /**
     * Enqueues captured publication intent for each route. Call within the workflow transaction for atomic state
     * and
     *  message persistence.
     * @param event event to deliver or inspect
     * @return the matching values in the order defined by this operation
     */
    public List<OutboxMessage> enqueue(WorkflowEvent event){return routing.route(event).stream().map(repository::enqueue).toList();
}}
