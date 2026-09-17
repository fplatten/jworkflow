package org.jworkflow.inbox;

import org.jworkflow.application.Command;

@FunctionalInterface
public interface InboxCommandDispatcher {
    Object dispatch(Command command);
}
