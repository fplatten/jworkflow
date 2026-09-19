package org.jworkflow.inbox;

import org.jworkflow.application.Command;

/**
 * Executes a translated application command for inbox processing. Implementations must use the enclosing
 * persistence transaction when workflow and inbox writes must be atomic.
 */
@FunctionalInterface
public interface InboxCommandDispatcher {
    /**
     * Executes one translated command within the caller's processing transaction.
     * @param command command to validate and execute
     * @return the resulting object
     */
    Object dispatch(Command command);
}
