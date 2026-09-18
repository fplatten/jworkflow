package org.jworkflow.model;

import org.jworkflow.inbox.InboxClaim;
import org.jworkflow.outbox.OutboxClaim;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ClaimValueObjectsTest {
    @Test
    void inboxClaimsValidateLeaseIdentityAndTime() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T12:00:00Z");
        Instant claimUntil = now.plusSeconds(30);
        Instant nearFuture = now.plusSeconds(1);
        InboxClaim claim = new InboxClaim(id, "worker", now, claimUntil);
        assertEquals(id, claim.messageId());
        assertEquals("worker", claim.ownerId());
        Instant futureFromCurrentTime = Instant.now().plusSeconds(30);
        assertNotNull(new InboxClaim(id, "worker", null, futureFromCurrentTime).claimedAt());
        assertThrows(NullPointerException.class, () -> new InboxClaim(null, "worker", now, nearFuture));
        assertThrows(IllegalArgumentException.class, () -> new InboxClaim(id, " ", now, nearFuture));
        assertThrows(NullPointerException.class, () -> new InboxClaim(id, "worker", now, null));
        assertThrows(IllegalArgumentException.class, () -> new InboxClaim(id, "worker", now, now));
    }

    @Test
    void outboxClaimsValidateLeaseIdentityAndTime() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T12:00:00Z");
        Instant claimUntil = now.plusSeconds(30);
        Instant nearFuture = now.plusSeconds(1);
        Instant past = now.minusSeconds(1);
        OutboxClaim claim = new OutboxClaim(id, "publisher", now, claimUntil);
        assertEquals(id, claim.messageId());
        assertEquals("publisher", claim.ownerId());
        Instant futureFromCurrentTime = Instant.now().plusSeconds(30);
        assertNotNull(new OutboxClaim(id, "publisher", null, futureFromCurrentTime).claimedAt());
        assertThrows(NullPointerException.class, () -> new OutboxClaim(null, "publisher", now, nearFuture));
        assertThrows(IllegalArgumentException.class, () -> new OutboxClaim(id, null, now, nearFuture));
        assertThrows(NullPointerException.class, () -> new OutboxClaim(id, "publisher", now, null));
        assertThrows(IllegalArgumentException.class, () -> new OutboxClaim(id, "publisher", now, past));
    }
}
