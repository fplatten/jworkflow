package org.jworkflow.query;
import org.jworkflow.model.WorkflowInstanceId;
import java.util.*;
/**
 * Immutable ordered history entries for one workflow instance. This value represents the complete requested
 * history, not a pagination cursor.
 * @param instanceId workflow instance identity
 * @param entries ordered immutable timeline entries
 */
public record WorkflowTimeline(WorkflowInstanceId instanceId,List<WorkflowTimelineEntry> entries){

    /**
     * Creates this value from the supplied components.
     * @param instanceId workflow instance identity
     * @param entries ordered immutable timeline entries
     */
    public WorkflowTimeline{entries=List.copyOf(entries);}}
