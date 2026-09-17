package org.jworkflow.query;
import org.jworkflow.outbox.*;import java.time.Instant;import java.util.UUID;
public record OutboxStateView(UUID messageId,UUID eventId,String destination,String idempotencyKey,OutboxMessageStatus status,
 int attemptCount,Instant nextAttemptAt,String lastError,String correlationId,String causationId,Instant createdAt,Instant publishedAt){
 public static OutboxStateView from(OutboxMessage m){return new OutboxStateView(m.messageId(),m.eventId(),m.destination(),m.idempotencyKey(),m.status(),m.attemptCount(),m.nextAttemptAt(),m.lastError(),m.correlationId(),m.causationId(),m.createdAt(),m.publishedAt());}}
