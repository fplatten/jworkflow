package org.jworkflow.persistence;

@FunctionalInterface
public interface WorkflowTransactionalWork<T> {
    T execute();
}
