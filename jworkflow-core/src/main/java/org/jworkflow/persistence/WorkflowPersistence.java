package org.jworkflow.persistence;

import org.jworkflow.events.*;

/**
 * Repository bundle and transaction boundary used by durable application services. Implementations must make
 * repositories participate in the same transaction to guarantee atomic workflow/message changes.
 */
public interface WorkflowPersistence {
    /**
     * Returns the immutable definition repository in this persistence bundle.
     * @return the immutable definition repository in this persistence bundle
     */
    WorkflowDefinitionRepository definitions();

    /**
     * Returns the snapshot repository in this persistence bundle.
     * @return the snapshot repository in this persistence bundle
     */
    WorkflowInstanceRepository instances();

    /**
     * Returns the append-only workflow history repository in this persistence bundle.
     * @return the append-only workflow history repository in this persistence bundle
     */
    WorkflowEventRepository events();

    /**
     * Returns the event delivery/attempt history repository in this persistence bundle.
     * @return the event delivery/attempt history repository in this persistence bundle
     */
    EventStatusRepository eventStatuses();

    /**
     * Returns the durable timer repository in this persistence bundle.
     * @return the durable timer repository in this persistence bundle
     */
    WorkflowTimerRepository timers();

    /**
     * Returns durable incoming-message storage; the compatibility default rejects this optional capability.
     * @return durable incoming-message storage; the compatibility default rejects this optional capability
     */
    default InboxRepository inbox() {
        throw new UnsupportedOperationException("Inbox persistence is not configured");
    }

    /**
     * Returns durable publication-intent storage; the compatibility default rejects this optional capability.
     * @return durable publication-intent storage; the compatibility default rejects this optional capability
     */
    default OutboxRepository outbox() {
        throw new UnsupportedOperationException("Outbox persistence is not configured");
    }

    /**
     * Returns command replay storage; the compatibility default rejects this optional capability.
     * @return command replay storage; the compatibility default rejects this optional capability
     */
    default CommandResultRepository commandResults() {
        throw new UnsupportedOperationException("Command-result persistence is not configured");
    }

    /**
     * Returns the transaction manager shared by this bundle's repositories.
     * @return the transaction manager shared by this bundle's repositories
     */
    WorkflowTransactionManager transactions();
}
