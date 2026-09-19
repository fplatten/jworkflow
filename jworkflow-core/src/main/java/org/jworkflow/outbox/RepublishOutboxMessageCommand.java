package org.jworkflow.outbox;
import org.jworkflow.application.Command;
    import java.time.Instant;
    import java.util.UUID;
/**
 * Audit-bearing request to publish an existing outbox message again.
 * @param messageId durable message identity
 * @param requestedBy host-provided actor requesting the operation
 * @param reason host-provided reason for manual intervention
 * @param requestedAt time at which the operation was requested
 */
public record RepublishOutboxMessageCommand(UUID messageId,String requestedBy,String reason,Instant requestedAt) implements Command {

    /**
     * Creates this value from the supplied components.
     * @param messageId durable message identity
     * @param requestedBy host-provided actor requesting the operation
     * @param reason host-provided reason for manual intervention
     * @param requestedAt time at which the operation was requested
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public RepublishOutboxMessageCommand{if(messageId==null)throw new IllegalArgumentException("messageId is required");
    requestedAt=requestedAt==null?Instant.now():requestedAt;
}}
