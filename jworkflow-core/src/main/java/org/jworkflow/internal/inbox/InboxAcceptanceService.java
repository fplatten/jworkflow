package org.jworkflow.internal.inbox;
import org.jworkflow.inbox.InboxInsertResult;
import org.jworkflow.inbox.*;

import org.jworkflow.internal.persistence.InboxRepository;
import org.jworkflow.internal.persistence.WorkflowTransactionManager;
import java.util.Objects;

/**
 * Captures and inserts inbound messages using their external source/ID identity. Matching redeliveries retain the
 * first stored message instead of replacing its payload.
 */
public final class InboxAcceptanceService {
    private final InboxRepository repository;
        private final WorkflowTransactionManager transactions;
    /**
     * Constructs InboxAcceptanceService with the supplied collaborators and configuration.
     * @param repository repository used by this service
     * @param transactions shared transaction manager coordinating related writes
     * @throws NullPointerException if repository, transactions is null
     */
    public InboxAcceptanceService(InboxRepository repository,WorkflowTransactionManager transactions){this.repository=Objects.requireNonNull(repository);
        this.transactions=Objects.requireNonNull(transactions);
    }
    /**
     * Inserts an inbound message or returns its first-arrival duplicate in the shared transaction.
     * @param message durable incoming message envelope
     * @return the stored message and whether this call inserted it
     * @throws NullPointerException if message is null
     */
    public InboxInsertResult accept(InboxMessage message){Objects.requireNonNull(message);
        InboxMessage received=message.asReceived();
        return transactions.inTransaction(()->repository.insertIfAbsent(received));
    }
}
