package org.jworkflow.query;

import org.jworkflow.events.*;
import java.time.Instant;
import java.util.*;

/**
 * Read-only event envelope and metadata included in a workflow timeline.
 * @param eventId event identity associated with the message or history row
 * @param eventName event name matched by workflow transitions or subscribers
 * @param occurredAt event occurrence time
 * @param receivedAt time the inbound message was received
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param sourceSystem external source identity used in routing, audit or inbox deduplication
 * @param message payload envelope and its media/schema metadata
 * @param headers event or command headers; secrets should be removed by the configured capture policy
 */
public record WorkflowTimelineEntry(UUID eventId,String eventName,Instant occurredAt,Instant receivedAt,String correlationId,
 String causationId,String sourceSystem,EventMessage message,Map<String,String> headers){
 /**
  * Creates this value from the supplied components.
  * @param eventId event identity associated with the message or history row
  * @param eventName event name matched by workflow transitions or subscribers
  * @param occurredAt event occurrence time
  * @param receivedAt time the inbound message was received
  * @param correlationId identity shared by related commands and events
  * @param causationId identity of the command or event that caused this work
  * @param sourceSystem external source identity used in routing, audit or inbox deduplication
  * @param message payload envelope and its media/schema metadata
  * @param headers event or command headers; secrets should be removed by the configured capture policy
  */
 public WorkflowTimelineEntry{headers=headers==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(headers));}
 /**
  * Copies event identity, timestamps, message and headers into a timeline entry.
  * @param e the event envelope
  * @return the resulting workflow timeline entry
  */
 public static WorkflowTimelineEntry from(WorkflowEvent e){EventMetadata m=e.metadata();return new WorkflowTimelineEntry(m.eventId(),m.eventName().value(),m.occurredAt(),m.receivedAt(),m.correlationId(),m.causationId(),m.sourceSystem(),e.message(),m.headers());}
}
