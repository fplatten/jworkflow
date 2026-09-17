package org.jworkflow.query;
import org.jworkflow.model.WorkflowInstanceId;
import java.util.*;
public record WorkflowTimeline(WorkflowInstanceId instanceId,List<WorkflowTimelineEntry> entries){public WorkflowTimeline{entries=List.copyOf(entries);}}
