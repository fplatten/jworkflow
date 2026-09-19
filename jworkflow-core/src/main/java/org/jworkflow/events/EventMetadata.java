package org.jworkflow.events;

import org.jworkflow.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable correlation, causation, trace, tenant and source metadata associated with an integration event.
 * Metadata does not establish tenant isolation or authentication.
 * @param eventId event identity associated with the message or history row
 * @param eventName event name matched by workflow transitions or subscribers
 * @param sourceSystem external source identity used in routing, audit or inbox deduplication
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param traceId host-provided distributed tracing identity
 * @param workflowInstanceId workflow instance identity associated with the operation
 * @param businessKey application business identity associated with the workflow
 * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
 * @param taxonomyVersion version of the host event naming/metadata taxonomy
 * @param occurredAt event occurrence time
 * @param receivedAt time the inbound message was received
 * @param headers event or command headers; secrets should be removed by the configured capture policy
 */
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
    /**
     * Creates this value from the supplied components.
     * @param eventId event identity associated with the message or history row
     * @param eventName event name matched by workflow transitions or subscribers
     * @param sourceSystem external source identity used in routing, audit or inbox deduplication
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param traceId host-provided distributed tracing identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param businessKey application business identity associated with the workflow
     * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
     * @param taxonomyVersion version of the host event naming/metadata taxonomy
     * @param occurredAt event occurrence time
     * @param receivedAt time the inbound message was received
     * @param headers event or command headers; secrets should be removed by the configured capture policy
     * @throws NullPointerException if eventName is null
     */
    public EventMetadata {
        eventId = eventId == null ? UUID.randomUUID() : eventId;
        Objects.requireNonNull(eventName, "eventName");
        sourceSystem = sourceSystem == null || sourceSystem.isBlank() ? "jworkflow" : sourceSystem;
        taxonomyVersion = taxonomyVersion == null || taxonomyVersion.isBlank() ? "1" : taxonomyVersion;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * Creates metadata for the event name with generated identity and default occurrence time.
     * @param eventName event name matched by workflow transitions or subscribers
     * @return the resulting event metadata
     */
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

    /**
     * Typed factory; the canonical string constructor remains for source compatibility.
     * @param eventId event identity associated with the message or history row
     * @param eventName event name matched by workflow transitions or subscribers
     * @param sourceSystem external source identity used in routing, audit or inbox deduplication
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param traceId host-provided distributed tracing identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param businessKey application business identity associated with the workflow
     * @param tenantId reserved tenant metadata; built-in durable routing rejects tenant-scoped routes
     * @param taxonomyVersion version of the host event naming/metadata taxonomy
     * @param occurredAt event occurrence time
     * @param receivedAt time the inbound message was received
     * @param headers event or command headers; secrets should be removed by the configured capture policy
     * @return the resulting event metadata
     */
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

    /**
     * Returns the correlation string as a typed identity, or null when absent.
     * @return the correlation string as a typed identity, or null when absent
     */
    public CorrelationId correlationIdentity() { return CorrelationId.of(correlationId); }
    /**
     * Returns the causation string as a typed identity, or null when absent.
     * @return the causation string as a typed identity, or null when absent
     */
    public CausationId causationIdentity() { return CausationId.of(causationId); }
    /**
     * Returns the trace string as a typed identity, or null when absent.
     * @return the trace string as a typed identity, or null when absent
     */
    public TraceId traceIdentity() { return TraceId.of(traceId); }

    /**
     * Copies metadata with workflow identities and merged additional headers.
     * @param correlationId identity shared by related commands and events
     * @param causationId identity of the command or event that caused this work
     * @param traceId host-provided distributed tracing identity
     * @param workflowInstanceId workflow instance identity associated with the operation
     * @param businessKey application business identity associated with the workflow
     * @param additionalHeaders headers merged into the copied event metadata
     * @return the resulting event metadata
     */
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
