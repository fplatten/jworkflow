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
    default List<InboxMessage> claimEligibleFenced(Instant now,String owner,Instant until,int limit){throw new UnsupportedOperationException("Fenced inbox claims are not supported");}
    /** Validate and hold this generation against reclaim until the enclosing transaction ends. */
    default void requireClaim(UUID id,String owner,String token){throw new UnsupportedOperationException("Fenced inbox claims are not supported");}
    default void markProcessed(UUID id,String owner,String token,Instant at){throw new UnsupportedOperationException("Fenced inbox completion is not supported");}
    default void scheduleRetry(UUID id,String owner,String token,Instant next,String error){throw new UnsupportedOperationException("Fenced inbox retry is not supported");}
    default void markDeadLetter(UUID id,String owner,String token,String error,Instant at){throw new UnsupportedOperationException("Fenced inbox dead letter is not supported");}
    void markProcessed(UUID messageId, String ownerId, Instant processedAt);
    void scheduleRetry(UUID messageId, String ownerId, Instant nextAttemptAt, String error);
    void markDeadLetter(UUID messageId, String ownerId, String error, Instant deadLetteredAt);
    int releaseExpiredClaims(Instant now);
    void appendAttempt(InboxAttempt attempt);
    List<InboxAttempt> findAttempts(UUID messageId);
    void requestReprocessing(UUID messageId, Instant requestedAt);
}
