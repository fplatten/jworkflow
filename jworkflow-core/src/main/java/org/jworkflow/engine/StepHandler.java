package org.jworkflow.engine;


@FunctionalInterface
public interface StepHandler {
    StepResult handle(StepContext context);
}
