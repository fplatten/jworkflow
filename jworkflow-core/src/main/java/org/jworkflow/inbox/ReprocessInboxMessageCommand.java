package org.jworkflow.inbox;

import org.jworkflow.application.Command;
import java.time.Instant;
import java.util.UUID;

public record ReprocessInboxMessageCommand(UUID messageId,String requestedBy,String reason,Instant requestedAt) implements Command {
    public ReprocessInboxMessageCommand {if(messageId==null)throw new IllegalArgumentException("messageId is required");requestedAt=requestedAt==null?Instant.now():requestedAt;}
}
