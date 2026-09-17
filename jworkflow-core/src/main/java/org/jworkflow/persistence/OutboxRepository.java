package org.jworkflow.persistence;

import org.jworkflow.outbox.OutboxAttempt;
import org.jworkflow.outbox.OutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository {
    OutboxMessage enqueue(OutboxMessage message);
    Optional<OutboxMessage> findById(UUID messageId);
    Optional<OutboxMessage> findByIdempotencyKey(String destination, String idempotencyKey);
    List<OutboxMessage> claimEligible(Instant now, String ownerId, Instant claimUntil, int limit);
    void markPublished(UUID messageId, String ownerId, Instant publishedAt);
    void scheduleRetry(UUID messageId, String ownerId, Instant nextAttemptAt, String error);
    void markDeadLetter(UUID messageId, String ownerId, String error, Instant deadLetteredAt);
    int releaseExpiredClaims(Instant now);
    void appendAttempt(OutboxAttempt attempt);
    List<OutboxAttempt> findAttempts(UUID messageId);
    void requestRepublishing(UUID messageId, Instant requestedAt);
    default List<OutboxMessage> findPending(int limit) { return List.of(); }
}
