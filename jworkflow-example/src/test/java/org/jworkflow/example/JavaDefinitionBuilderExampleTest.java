package org.jworkflow.example;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.StepResult;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSignal;
import org.jworkflow.model.WorkflowStatus;

import java.time.Instant;
import java.util.Map;

/** Test-oriented example of defining and registering a workflow without Groovy. */
public final class JavaDefinitionBuilderExampleTest {
    private JavaDefinitionBuilderExampleTest() {
    }

    public static void main(String[] args) throws Exception {
        WorkflowDefinitionBuilder definition = WorkflowDefinitionBuilder.workflow("embedded-order")
                .version("1.0.0")
                .startAt("validate")
                .step("validate", step -> step.action("order.validate").onSuccess("accepted"))
                .end("accepted");

        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .stepHandler("order.validate", context -> StepResult.success())
                .build()) {
            WorkflowInstanceId id = engine.start(
                    "embedded-order", "order-java-1", Map.of("amount", 125));
            engine.signal(id, new WorkflowSignal(
                    "order.submitted", "order-java-1", null, "order-java-1", Instant.now(), Map.of()));
            if (engine.snapshot(id).status() != WorkflowStatus.COMPLETED) {
                throw new AssertionError("Java builder example did not complete");
            }
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
