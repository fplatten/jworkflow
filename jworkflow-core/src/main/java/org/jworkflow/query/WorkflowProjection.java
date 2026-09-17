package org.jworkflow.query;
import org.jworkflow.events.WorkflowEvent;
@FunctionalInterface public interface WorkflowProjection { void onEvent(WorkflowEvent event); }
