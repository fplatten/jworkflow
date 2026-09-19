package org.jworkflow.outbox;
import org.jworkflow.events.WorkflowEvent;import java.util.List;
/**
 * Maps a workflow event to zero or more publication destinations. Transport execution is the publisher's
 * responsibility.
 */
@FunctionalInterface public interface OutboxRouter {

    /**
     * Returns the destinations to which this event should be published; an empty list suppresses publication
     * intent.
     * @param event event to deliver or inspect
     * @return the matching values in the order defined by this operation
     */
    List<String> destinations(WorkflowEvent event);}
