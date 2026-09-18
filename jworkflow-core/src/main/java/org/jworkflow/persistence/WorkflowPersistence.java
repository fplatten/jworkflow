package org.jworkflow.persistence;

import org.jworkflow.events.*;

public interface WorkflowPersistence {
    WorkflowDefinitionRepository definitions();

    WorkflowInstanceRepository instances();

    WorkflowEventRepository events();

    EventStatusRepository eventStatuses();

    WorkflowTimerRepository timers();

    default InboxRepository inbox() {
        throw new UnsupportedOperationException("Inbox persistence is not configured");
    }

    default OutboxRepository outbox() {
        throw new UnsupportedOperationException("Outbox persistence is not configured");
    }

    default CommandResultRepository commandResults() {
        throw new UnsupportedOperationException("Command-result persistence is not configured");
    }

    WorkflowTransactionManager transactions();
}
