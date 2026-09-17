package org.jworkflow.outbox;
import org.jworkflow.events.WorkflowEvent;import org.jworkflow.persistence.*;import java.util.*;
public final class OutboxEnqueueService {private final OutboxRepository repository;private final OutboxRoutingService routing;public OutboxEnqueueService(OutboxRepository repository,OutboxRoutingService routing){this.repository=Objects.requireNonNull(repository);this.routing=Objects.requireNonNull(routing);}public List<OutboxMessage> enqueue(WorkflowEvent event){return routing.route(event).stream().map(repository::enqueue).toList();}}
