package org.jworkflow.jdbc;

import org.jworkflow.inbox.InboxMessage;
import org.jworkflow.observability.WorkflowLifecycleEvent;
import org.jworkflow.observability.WorkflowLifecycleEventType;
import org.jworkflow.observability.WorkflowLifecycleObserver;
import org.jworkflow.outbox.OutboxMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Releases expired inbox and outbox leases and reports every message a sweep dead-letters. Must run inside a
 * transaction; dead-letter events are delivered after it commits, like the other lifecycle events.
 */
final class JdbcExpiredLeases {
    static final String LEASE_EXPIRED = "lease_expired";

    private JdbcExpiredLeases() {
    }

    static void releaseInbox(JdbcWorkflowPersistence persistence, Instant now, WorkflowLifecycleObserver observer, Clock clock) {
        for (UUID id : persistence.jdbcInbox().releaseExpired(now).deadLettered()) {
            InboxMessage message = persistence.inbox().findById(id).orElse(null);
            if (message == null) continue;
            WorkflowLifecycleEvent event = new WorkflowLifecycleEvent(WorkflowLifecycleEventType.INBOX_DEAD_LETTERED,
                    clock.instant(), null, null, null, null, null, message.correlationId(), message.causationId(), null,
                    Map.of("messageId", id.toString(), "sourceSystem", message.sourceSystem(), "failureCategory", LEASE_EXPIRED));
            persistence.transactions().afterCommit(() -> observer.observe(event));
        }
    }

    static void releaseOutbox(JdbcWorkflowPersistence persistence, Instant now, WorkflowLifecycleObserver observer, Clock clock) {
        for (UUID id : persistence.jdbcOutbox().releaseExpired(now).deadLettered()) {
            OutboxMessage message = persistence.outbox().findById(id).orElse(null);
            if (message == null) continue;
            WorkflowLifecycleEvent event = new WorkflowLifecycleEvent(WorkflowLifecycleEventType.OUTBOX_DEAD_LETTERED,
                    clock.instant(), null, null, null, null, null, message.correlationId(), message.causationId(), null,
                    Map.of("messageId", id.toString(), "destination", message.destination(), "failureCategory", LEASE_EXPIRED));
            persistence.transactions().afterCommit(() -> observer.observe(event));
        }
    }
}
