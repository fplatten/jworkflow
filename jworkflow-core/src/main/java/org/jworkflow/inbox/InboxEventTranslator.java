package org.jworkflow.inbox;

import org.jworkflow.application.Command;
import java.util.List;

/**
 * Maps one accepted inbox message to the workflow commands that should execute for it. Translation does not by
 * itself commit or acknowledge the message.
 */
@FunctionalInterface
public interface InboxEventTranslator {
    /**
     * Returns the commands required for one accepted message; translation alone does not mark it processed.
     * @param message durable incoming message envelope
     * @return the matching values in the order defined by this operation
     */
    List<Command> translate(InboxMessage message);
}
