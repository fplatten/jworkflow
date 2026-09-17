package org.jworkflow.outbox;
import org.jworkflow.application.Command;import java.time.Instant;import java.util.UUID;
public record RepublishOutboxMessageCommand(UUID messageId,String requestedBy,String reason,Instant requestedAt) implements Command {public RepublishOutboxMessageCommand{if(messageId==null)throw new IllegalArgumentException("messageId is required");requestedAt=requestedAt==null?Instant.now():requestedAt;}}
