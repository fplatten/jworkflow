package org.jworkflow.jdbc;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.events.IntegrationEvent;
import org.jworkflow.inbox.*;
import java.util.Objects;

/** Infrastructure boundary converting an external event envelope into the durable inbox model. */
public final class JdbcInboxEventAdapter {
    private final JdbcInboxApplication application;
    /**
     * Constructs JdbcInboxEventAdapter with the supplied collaborators and configuration.
     * @param application JDBC application adapter coordinating message processing
     * @throws NullPointerException if application is null
     */
    public JdbcInboxEventAdapter(JdbcInboxApplication application){this.application=Objects.requireNonNull(application);}
    /**
     * Converts an event to an inbox envelope using source/event ID deduplication, accepts it and polls newly
     * eligible work.
     * @param event event to deliver or inspect
     * @return the stored message and whether this call inserted it
     * @throws NullPointerException if event is null
     */
    public InboxInsertResult onEvent(WorkflowEvent event){Objects.requireNonNull(event);return application.acceptAndProcess(new InboxMessage(null,event.metadata().eventId().toString(),event.metadata().sourceSystem(),event.message(),event.metadata().correlationId(),event.metadata().causationId(),event.metadata().receivedAt(),null,null,0,null,null,null,null));}
    /**
     * Converts an event to an inbox envelope using source/event ID deduplication, accepts it and polls newly
     * eligible work.
     * @param event event to deliver or inspect
     * @return the stored message and whether this call inserted it
     * @throws NullPointerException if event is null
     */
    public InboxInsertResult onEvent(IntegrationEvent event){return onEvent(Objects.requireNonNull(event,"event").toWorkflowEvent());}
}
