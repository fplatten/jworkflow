package org.jworkflow.query;

import org.jworkflow.events.*;
import java.time.Instant;
import java.util.*;

public record WorkflowTimelineEntry(UUID eventId,String eventName,Instant occurredAt,Instant receivedAt,String correlationId,
 String causationId,String sourceSystem,EventMessage message,Map<String,String> headers){
 public WorkflowTimelineEntry{headers=headers==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(headers));}
 public static WorkflowTimelineEntry from(WorkflowEvent e){EventMetadata m=e.metadata();return new WorkflowTimelineEntry(m.eventId(),m.eventName().value(),m.occurredAt(),m.receivedAt(),m.correlationId(),m.causationId(),m.sourceSystem(),e.message(),m.headers());}
}
