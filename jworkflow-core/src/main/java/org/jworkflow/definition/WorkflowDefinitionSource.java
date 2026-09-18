package org.jworkflow.definition;


import java.util.List;

public interface WorkflowDefinitionSource {
    List<WorkflowDefinitionText> load();
}
