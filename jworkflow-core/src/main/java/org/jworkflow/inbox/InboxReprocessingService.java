package org.jworkflow.inbox;
import org.jworkflow.persistence.*;
    import java.util.Objects;
/**
 * Requests manual reprocessing of an existing inbox message while retaining its original identity, payload and
 * prior attempts.
 */
public final class InboxReprocessingService {private final InboxRepository inbox;
    private final WorkflowTransactionManager transactions;
    /**
     * Constructs InboxReprocessingService with the supplied collaborators and configuration.
     * @param inbox durable inbound-message repository
     * @param transactions shared transaction manager coordinating related writes
     * @throws NullPointerException if inbox, transactions is null
     */
    public InboxReprocessingService(InboxRepository inbox,WorkflowTransactionManager transactions){this.inbox=Objects.requireNonNull(inbox);
    this.transactions=Objects.requireNonNull(transactions);
}

    /**
     * Schedules a stored message for another processing attempt while preserving its identity and original
     * payload.
     * @param command command to validate and execute
     * @return the resulting inbox message
     */
    public InboxMessage reprocess(ReprocessInboxMessageCommand command){return transactions.inTransaction(()->{InboxMessage current=inbox.findById(command.messageId()).orElseThrow(()->new InboxReprocessingException("Inbox message not found: "+command.messageId()));
    if(current.status()!=InboxMessageStatus.DEAD_LETTER&&current.status()!=InboxMessageStatus.RETRY_SCHEDULED)throw new InboxReprocessingException("Inbox message cannot be reprocessed from "+current.status());
    inbox.requestReprocessing(command.messageId(),command.requestedAt());
    return inbox.findById(command.messageId()).orElseThrow();
});
}}
