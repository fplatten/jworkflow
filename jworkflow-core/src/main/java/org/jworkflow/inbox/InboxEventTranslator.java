package org.jworkflow.inbox;

import org.jworkflow.application.Command;
import java.util.List;

@FunctionalInterface
public interface InboxEventTranslator {
    List<Command> translate(InboxMessage message);
}
