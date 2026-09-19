package org.jworkflow.persistence;

/**
 * Persistence work returning a value on successful completion. Exceptions abort the enclosing transaction rather
 * than authorizing handler replay.
 * @param <T> the value type
 */
@FunctionalInterface
public interface WorkflowTransactionalWork<T> {
    /**
     * Performs the unit of work and returns its result on successful completion.
     * @return the value produced by the work
     */
    T execute();
}
