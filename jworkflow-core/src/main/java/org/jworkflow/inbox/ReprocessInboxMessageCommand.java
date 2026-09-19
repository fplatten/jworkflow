package org.jworkflow.inbox;

import org.jworkflow.application.Command;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit-bearing request to reprocess a stored inbox message without replacing the original envelope.
 * @param messageId durable message identity
 * @param requestedBy host-provided actor requesting the operation
 * @param reason host-provided reason for manual intervention
 * @param requestedAt time at which the operation was requested
 */
public record ReprocessInboxMessageCommand(UUID messageId,String requestedBy,String reason,Instant requestedAt) implements Command {
    /**
     * Creates this value from the supplied components.
     * @param messageId durable message identity
     * @param requestedBy host-provided actor requesting the operation
     * @param reason host-provided reason for manual intervention
     * @param requestedAt time at which the operation was requested
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public ReprocessInboxMessageCommand {if(messageId==null)throw new IllegalArgumentException("messageId is required");
        requestedAt=requestedAt==null?Instant.now():requestedAt;
    }
}
