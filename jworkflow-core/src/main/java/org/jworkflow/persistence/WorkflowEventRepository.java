package org.jworkflow.persistence;

import org.jworkflow.events.*;
import org.jworkflow.model.*;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Append-only workflow event history. Events for an instance use per-instance sequence numbers; callers must share
 * the command transaction to append history atomically with state. The legacy time-only cursor does not
 * distinguish equal timestamps.
 */
public interface WorkflowEventRepository {
    /**
     * Appends immutable event history using per-instance sequence allocation where the event identifies an
     * instance.
     * @param event event to deliver or inspect
     */
    void append(WorkflowEvent event);

    /**
     * Looks up an immutable event by its event ID.
     * @param eventId event identity associated with the message or history row
     * @return the matching value, or an empty optional when absent
     */
    Optional<WorkflowEvent> find(UUID eventId);

    /**
     * Returns the values associated with an instance; full-history repository methods are not bounded by a page
     * size.
     * @param instanceId workflow instance identity
     * @return the matching values in the order defined by this operation
     */
    List<WorkflowEvent> findByWorkflowInstance(WorkflowInstanceId instanceId);

    /**
     * Returns a bounded event-time window strictly after the supplied instant. This legacy time-only cursor can
     * omit ties split across pages.
     * @param afterExclusive exclusive event-time lower bound; equal-time ties require a composite cursor for
     *     lossless traversal
     * @param limit maximum number of rows requested
     * @return the matching values in the order defined by this operation
     */
    default List<WorkflowEvent> findAllAfter(java.time.Instant afterExclusive, int limit) { return List.of(); }
}
