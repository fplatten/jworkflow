package org.jworkflow.jdbc;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.events.IntegrationEvent;
import org.jworkflow.inbox.*;
import java.util.Objects;

/** Infrastructure boundary converting an external event envelope into the durable inbox model. */
public final class JdbcInboxEventAdapter {
    private final JdbcInboxApplication application;
    public JdbcInboxEventAdapter(JdbcInboxApplication application){this.application=Objects.requireNonNull(application);}
    public InboxInsertResult onEvent(WorkflowEvent event){Objects.requireNonNull(event);return application.acceptAndProcess(new InboxMessage(null,event.metadata().eventId().toString(),event.metadata().sourceSystem(),event.message(),event.metadata().correlationId(),event.metadata().causationId(),event.metadata().receivedAt(),null,null,0,null,null,null,null));}
    public InboxInsertResult onEvent(IntegrationEvent event){return onEvent(Objects.requireNonNull(event,"event").toWorkflowEvent());}
}
