package org.jworkflow.query;
import org.jworkflow.outbox.*;import java.time.Instant;import java.util.UUID;
/**
 * Read-only publication state exposed by workflow queries, without performing a send or acquiring a lease.
 * @param messageId durable message identity
 * @param eventId event identity associated with the message or history row
 * @param destination registered publication destination
 * @param idempotencyKey complete replay/deduplication key; retain the same key when reconciling an uncertain
 *      outcome
 * @param status publication state
 * @param attemptCount number of processing attempts already recorded
 * @param nextAttemptAt deadline for the next eligible attempt; null where no retry is scheduled
 * @param lastError most recently recorded failure detail
 * @param correlationId identity shared by related commands and events
 * @param causationId identity of the command or event that caused this work
 * @param createdAt creation time
 * @param publishedAt time successful publication was recorded
 */
public record OutboxStateView(UUID messageId,UUID eventId,String destination,String idempotencyKey,OutboxMessageStatus status,
 int attemptCount,Instant nextAttemptAt,String lastError,String correlationId,String causationId,Instant createdAt,Instant publishedAt){
 /**
  * Copies publication state and diagnostic metadata from the stored outbox envelope.
  * @param m the pending publication
  * @return the resulting outbox state view
  */
 public static OutboxStateView from(OutboxMessage m){return new OutboxStateView(m.messageId(),m.eventId(),m.destination(),m.idempotencyKey(),m.status(),m.attemptCount(),m.nextAttemptAt(),m.lastError(),m.correlationId(),m.causationId(),m.createdAt(),m.publishedAt());}}
