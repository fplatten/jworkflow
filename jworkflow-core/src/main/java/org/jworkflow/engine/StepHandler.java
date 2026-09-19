package org.jworkflow.engine;


/**
 * Application callback invoked while calculating a workflow step. JDBC execution can call handlers inside a
 * database transaction; external effects are not rolled back and must tolerate caller retries.
 */
@FunctionalInterface
public interface StepHandler {
    /**
     * Executes application step logic and returns success/failure plus variable updates. External effects are not
     *  rolled back with JDBC work.
     * @param context instance identity, step inputs and triggering signal for the handler
     * @return the resulting step result
     */
    StepResult handle(StepContext context);
}
