package org.jworkflow.inbox;
import org.jworkflow.persistence.*;
    import java.util.Objects;
public final class InboxReprocessingService {private final InboxRepository inbox;
    private final WorkflowTransactionManager transactions;
    public InboxReprocessingService(InboxRepository inbox,WorkflowTransactionManager transactions){this.inbox=Objects.requireNonNull(inbox);
    this.transactions=Objects.requireNonNull(transactions);
}public InboxMessage reprocess(ReprocessInboxMessageCommand command){return transactions.inTransaction(()->{InboxMessage current=inbox.findById(command.messageId()).orElseThrow(()->new InboxReprocessingException("Inbox message not found: "+command.messageId()));
    if(current.status()!=InboxMessageStatus.DEAD_LETTER&&current.status()!=InboxMessageStatus.RETRY_SCHEDULED)throw new InboxReprocessingException("Inbox message cannot be reprocessed from "+current.status());
    inbox.requestReprocessing(command.messageId(),command.requestedAt());
    return inbox.findById(command.messageId()).orElseThrow();
});
}}
