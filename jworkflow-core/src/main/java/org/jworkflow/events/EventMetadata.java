package org.jworkflow.events;

import org.jworkflow.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventMetadata(
        UUID eventId,
        EventName eventName,
        String sourceSystem,
        String correlationId,
        String causationId,
        String traceId,
        WorkflowInstanceId workflowInstanceId,
        String businessKey,
        String tenantId,
        String taxonomyVersion,
        Instant occurredAt,
        Instant receivedAt,
        Map<String, String> headers
) {
    public EventMetadata {
        eventId = eventId == null ? UUID.randomUUID() : eventId;
        Objects.requireNonNull(eventName, "eventName");
        sourceSystem = sourceSystem == null || sourceSystem.isBlank() ? "jworkflow" : sourceSystem;
        taxonomyVersion = taxonomyVersion == null || taxonomyVersion.isBlank() ? "1" : taxonomyVersion;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static EventMetadata named(String eventName) {
        return new EventMetadata(
                null,
                new EventName(eventName),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    /** Typed factory; the canonical string constructor remains for source compatibility. */
    public static EventMetadata withIdentities(UUID eventId, EventName eventName, String sourceSystem,
            CorrelationId correlationId, CausationId causationId, TraceId traceId,
            WorkflowInstanceId workflowInstanceId, String businessKey, String tenantId,
            String taxonomyVersion, Instant occurredAt, Instant receivedAt, Map<String, String> headers) {
        return new EventMetadata(eventId, eventName, sourceSystem,
                correlationId == null ? null : correlationId.value(),
                causationId == null ? null : causationId.value(),
                traceId == null ? null : traceId.value(), workflowInstanceId, businessKey, tenantId,
                taxonomyVersion, occurredAt, receivedAt, headers);
    }

    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    public CausationId causationIdentity() { return CausationId.of(causationId); }
    public TraceId traceIdentity() { return TraceId.of(traceId); }

    public EventMetadata withWorkflowContext(
            String correlationId,
            String causationId,
            String traceId,
            WorkflowInstanceId workflowInstanceId,
            String businessKey,
            Map<String, String> additionalHeaders
    ) {
        java.util.LinkedHashMap<String, String> mergedHeaders = new java.util.LinkedHashMap<>(headers);
        if (additionalHeaders != null) {
            mergedHeaders.putAll(additionalHeaders);
        }
        return new EventMetadata(
                eventId,
                eventName,
                sourceSystem,
                valueOrCurrent(this.correlationId, correlationId),
                valueOrCurrent(this.causationId, causationId),
                valueOrCurrent(this.traceId, traceId),
                this.workflowInstanceId == null ? workflowInstanceId : this.workflowInstanceId,
                valueOrCurrent(this.businessKey, businessKey),
                tenantId,
                taxonomyVersion,
                occurredAt,
                receivedAt,
                mergedHeaders);
    }

    private static String valueOrCurrent(String current, String fallback) {
        return current == null || current.isBlank() ? fallback : current;
    }

}
