package org.jworkflow.persistence;

import org.jworkflow.inbox.InboxAttempt;
import org.jworkflow.inbox.InboxInsertResult;
import org.jworkflow.inbox.InboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboxRepository {
    InboxInsertResult insertIfAbsent(InboxMessage message);
    Optional<InboxMessage> findById(UUID messageId);
    Optional<InboxMessage> findByExternalIdentity(String sourceSystem, String externalEventId);
    List<InboxMessage> claimEligible(Instant now, String ownerId, Instant claimUntil, int limit);
    void markProcessed(UUID messageId, String ownerId, Instant processedAt);
    void scheduleRetry(UUID messageId, String ownerId, Instant nextAttemptAt, String error);
    void markDeadLetter(UUID messageId, String ownerId, String error, Instant deadLetteredAt);
    int releaseExpiredClaims(Instant now);
    void appendAttempt(InboxAttempt attempt);
    List<InboxAttempt> findAttempts(UUID messageId);
    void requestReprocessing(UUID messageId, Instant requestedAt);
}
