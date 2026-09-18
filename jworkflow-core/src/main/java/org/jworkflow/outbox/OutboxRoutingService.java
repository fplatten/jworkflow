package org.jworkflow.outbox;
import org.jworkflow.events.*;
    import java.util.*;
public final class OutboxRoutingService {private final OutboxRouter router;
    public OutboxRoutingService(OutboxRouter router){this.router=Objects.requireNonNull(router);
}public List<OutboxMessage> route(WorkflowEvent event){LinkedHashSet<String> destinations=new LinkedHashSet<>(router.destinations(event));
    ArrayList<OutboxMessage> result=new ArrayList<>();
    LinkedHashMap<String,String> attributes=new LinkedHashMap<>(event.message().attributes());
    attributes.put("eventName",event.eventName().value());
    attributes.put("sourceSystem",event.metadata().sourceSystem());
    attributes.put("taxonomyVersion",event.metadata().taxonomyVersion());
    EventMessage message=new EventMessage(event.message().payload(),event.message().contentType(),event.message().schemaName(),event.message().schemaVersion(),event.message().redacted(),attributes);
    for(String destination:destinations){if(destination==null||destination.isBlank())throw new IllegalArgumentException("outbox destination is required");
    result.add(new OutboxMessage(null,event.metadata().eventId(),destination,event.metadata().eventId()+":"+destination,message,event.metadata().correlationId(),event.metadata().causationId(),event.metadata().occurredAt(),null,null,0,null,null,null,null));
}return List.copyOf(result);
}}
