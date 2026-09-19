package org.jworkflow.outbox;
import org.jworkflow.persistence.*;
    import java.util.Objects;
/**
 * Requests another publication attempt for a stored message without replacing its original payload or attempt
 * history.
 */
public final class OutboxRepublishingService {private final OutboxRepository repository;
    private final WorkflowTransactionManager transactions;
    /**
     * Constructs OutboxRepublishingService with the supplied collaborators and configuration.
     * @param repository repository used by this service
     * @param transactions shared transaction manager coordinating related writes
     * @throws NullPointerException if repository, transactions is null
     */
    public OutboxRepublishingService(OutboxRepository repository,WorkflowTransactionManager transactions){this.repository=Objects.requireNonNull(repository);
    this.transactions=Objects.requireNonNull(transactions);
}

    /**
     * Schedules the existing message for another publication attempt without replacing payload or prior history.
     * @param command command to validate and execute
     * @return the resulting outbox message
     */
    public OutboxMessage republish(RepublishOutboxMessageCommand command){return transactions.inTransaction(()->{OutboxMessage current=repository.findById(command.messageId()).orElseThrow(()->new OutboxRepublishException("Outbox message not found: "+command.messageId()));
    if(current.status()!=OutboxMessageStatus.DEAD_LETTER&&current.status()!=OutboxMessageStatus.RETRY_SCHEDULED)throw new OutboxRepublishException("Outbox message cannot be republished from "+current.status());
    repository.requestRepublishing(current.messageId(),command.requestedAt());
    return repository.findById(current.messageId()).orElseThrow();
});
}}
