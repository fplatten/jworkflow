package org.jworkflow.outbox;
import org.jworkflow.events.WorkflowEvent;import java.util.List;
@FunctionalInterface public interface OutboxRouter {List<String> destinations(WorkflowEvent event);}
