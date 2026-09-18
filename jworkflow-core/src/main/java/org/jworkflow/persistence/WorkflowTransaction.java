package org.jworkflow.persistence;


@FunctionalInterface
public interface WorkflowTransaction {
    void execute();
}
