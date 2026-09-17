package org.jworkflow.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InboxClaim(UUID messageId, String ownerId, Instant claimedAt, Instant claimUntil) {
    public InboxClaim {
        Objects.requireNonNull(messageId, "messageId");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        claimedAt = claimedAt == null ? Instant.now() : claimedAt;
        Objects.requireNonNull(claimUntil, "claimUntil");
        if (!claimUntil.isAfter(claimedAt)) throw new IllegalArgumentException("claimUntil must be after claimedAt");
    }
}
