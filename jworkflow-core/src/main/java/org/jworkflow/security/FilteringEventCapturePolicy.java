package org.jworkflow.security;

import org.jworkflow.events.*;
import java.util.*;

/** Removes configured headers/message attributes and optionally redacts the payload. */
public final class FilteringEventCapturePolicy implements EventCapturePolicy {
    private final Set<String> removedHeaders;
    private final Set<String> removedAttributes;
    private final boolean redactPayload;

    /**
     * Constructs FilteringEventCapturePolicy with the supplied collaborators and configuration.
     * @param removedHeaders header names removed by the capture policy
     * @param removedAttributes message attribute names removed by the capture policy
     * @param redactPayload whether to remove the body and mark the message as redacted
     */
    public FilteringEventCapturePolicy(Set<String> removedHeaders, Set<String> removedAttributes,
            boolean redactPayload) {
        this.removedHeaders = normalized(removedHeaders);
        this.removedAttributes = normalized(removedAttributes);
        this.redactPayload = redactPayload;
    }

    /**
     * Creates a policy removing the named headers while retaining message attributes and payload.
     * @param headers event or command headers; secrets should be removed by the configured capture policy
     * @return the resulting filtering event capture policy
     */
    public static FilteringEventCapturePolicy removeHeaders(Set<String> headers) {
        return new FilteringEventCapturePolicy(headers, Set.of(), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override public WorkflowEvent filter(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");
        EventMetadata metadata = event.metadata();
        Map<String,String> headers = filtered(metadata.headers(), removedHeaders);
        EventMetadata safeMetadata = new EventMetadata(metadata.eventId(), metadata.eventName(), metadata.sourceSystem(),
                metadata.correlationId(), metadata.causationId(), metadata.traceId(), metadata.workflowInstanceId(),
                metadata.businessKey(), metadata.tenantId(), metadata.taxonomyVersion(), metadata.occurredAt(),
                metadata.receivedAt(), headers);
        EventMessage message = event.message();
        Map<String,String> attributes = filtered(message.attributes(), removedAttributes);
        EventMessage safeMessage = new EventMessage(redactPayload ? null : message.payload(), message.contentType(),
                message.schemaName(), message.schemaVersion(), message.redacted() || redactPayload, attributes);
        return new WorkflowEvent(safeMetadata, safeMessage);
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("filter names must not be blank");
            result.add(value);
        }
        return Set.copyOf(result);
    }

    private static Map<String,String> filtered(Map<String,String> values, Set<String> removed) {
        if (values.isEmpty() || removed.isEmpty()) return values;
        LinkedHashMap<String,String> result = new LinkedHashMap<>(values);
        removed.forEach(result::remove);
        return Collections.unmodifiableMap(result);
    }
}
