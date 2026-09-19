package org.jworkflow.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Message identity and lease acquisition metadata, including an optional opaque generation token. The
 * compatibility constructor omits the token and does not provide generation fencing.
 * @param messageId durable message identity
 * @param ownerId worker identity; not a substitute for an acquisition token
 * @param claimedAt time the lease was acquired
 * @param claimUntil lease expiration instant, after the acquisition time
 * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
 */
public record InboxClaim(UUID messageId, String ownerId, Instant claimedAt, Instant claimUntil, String claimToken) {
    /**
     * Creates a legacy value without an acquisition token. This constructor does not provide lease-generation
     * fencing.
     * @param messageId durable message identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param claimedAt time the lease was acquired
     * @param claimUntil lease expiration instant, after the acquisition time
     */
    public InboxClaim(UUID messageId,String ownerId,Instant claimedAt,Instant claimUntil){this(messageId,ownerId,claimedAt,claimUntil,null);}
    /**
     * Creates this value from the supplied components.
     * @param messageId durable message identity
     * @param ownerId worker identity; not a substitute for an acquisition token
     * @param claimedAt time the lease was acquired
     * @param claimUntil lease expiration instant, after the acquisition time
     * @param claimToken opaque token identifying the acquisition generation; null for legacy/unclaimed values
     * @throws NullPointerException if messageId, claimUntil is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public InboxClaim {
        if(claimToken!=null&&claimToken.isBlank())throw new IllegalArgumentException("claimToken must not be blank");
        Objects.requireNonNull(messageId, "messageId");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        claimedAt = claimedAt == null ? Instant.now() : claimedAt;
        Objects.requireNonNull(claimUntil, "claimUntil");
        if (!claimUntil.isAfter(claimedAt)) throw new IllegalArgumentException("claimUntil must be after claimedAt");
    }
}
