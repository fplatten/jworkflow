package org.jworkflow.inbox;

import org.jworkflow.persistence.InboxRepository;
import org.jworkflow.persistence.WorkflowTransactionManager;
import java.util.Objects;

public final class InboxAcceptanceService {
    private final InboxRepository repository;
        private final WorkflowTransactionManager transactions;
    public InboxAcceptanceService(InboxRepository repository,WorkflowTransactionManager transactions){this.repository=Objects.requireNonNull(repository);
        this.transactions=Objects.requireNonNull(transactions);
    }
    public InboxInsertResult accept(InboxMessage message){Objects.requireNonNull(message);
        return transactions.inTransaction(()->repository.insertIfAbsent(message));
    }
}
