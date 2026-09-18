package org.jworkflow.definition;

import org.jworkflow.engine.InMemoryWorkflowEngine;
import org.jworkflow.engine.StepResult;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.engine.WorkflowValidationException;
import org.jworkflow.model.GatewayType;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.model.WorkflowNodeType;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.model.WorkflowTransition;
import org.jworkflow.model.WorkflowSignal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Executable acceptance checks for the optional Java definition API. */
public final class JavaDefinitionBuilderContractTest {
    private JavaDefinitionBuilderContractTest() {
    }

    public static void main(String[] args) throws Exception {
        buildsAndRunsAJavaOnlyWorkflow();
        buildsEveryDocumentedNodeShape();
        rejectsInvalidDefinitionsBeforeRegistration();
    }

    private static void buildsAndRunsAJavaOnlyWorkflow() throws Exception {
        WorkflowDefinitionBuilder builder = WorkflowDefinitionBuilder.workflow("java-order")
                .version("1.0.0")
                .startAt("reserve")
                .step("reserve", step -> step
                        .action("inventory.reserve")
                        .onSuccess("complete"))
                .end("complete");

        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(builder)
                .stepHandler("inventory.reserve", context -> StepResult.success())
                .build()) {
            WorkflowInstanceId id = engine.start("java-order", "order-42", Map.of("sku", "A-1"));
            engine.signal(id, new WorkflowSignal(
                    "inventory.requested", "order-42", null, "order-42", Instant.now(), Map.of()));
            require(engine.snapshot(id).status() == WorkflowStatus.COMPLETED,
                    "Java-authored workflow did not complete");
        }

        WorkflowDefinition expected = WorkflowDefinition.of(
                "model-equivalence", "1", "work",
                WorkflowNode.step("work", "work.execute",
                        java.util.List.of(new WorkflowTransition("success", "done", null, null))),
                WorkflowNode.end("done"));
        WorkflowDefinition actual = WorkflowDefinitionBuilder.workflow("model-equivalence")
                .version("1")
                .startAt("work")
                .step("work", step -> step.action("work.execute").onSuccess("done"))
                .end("done")
                .build();
        require(expected.equals(actual), "Builder output differs from the immutable definition model");
    }

    private static void buildsEveryDocumentedNodeShape() {
        WorkflowDefinition definition = WorkflowDefinitionBuilder.workflow("all-java-nodes")
                .version("2.0.0")
                .startWhen("order.created")
                .correlateBy("orderId")
                .step("start", step -> step
                        .listener("orders", "validate")
                        .retry(retry -> retry.maxAttempts(3).backoff(Duration.ofSeconds(1)))
                        .timeout(timeout -> timeout.after(Duration.ofMinutes(1)).goTo("failed"))
                        .onSuccess("route")
                        .onFailure("failed"))
                .branch("route", gateway -> gateway
                        .when(branch -> branch.named("parallel")
                                .variable("parallel", "eq", true).goTo("fork"))
                        .otherwise("wait"))
                .fork("fork", fork -> fork
                        .branch("charge", "charge")
                        .branch("stock", "stock")
                        .joinAt("joined"))
                .step("charge", step -> step.action("payment.charge").onSuccess("joined"))
                .step("stock", step -> step.action("inventory.reserve").onSuccess("joined"))
                .join("joined", join -> join.require("charge", "stock").then("loop"))
                .loop("loop", loop -> loop.whileVariable("attempt", "lt", 2)
                        .maxIterations(2).doStep("loop-step").then("child"))
                .step("loop-step", step -> step.action("attempt.increment").onSuccess("loop"))
                .subWorkflow("child", child -> child.workflow("child-order", "1")
                        .input("orderId", "orderId")
                        .onSuccess("child.completed", "complete")
                        .onFailure("child.failed", "failed"))
                .waitFor("wait", wait -> wait.event("approval.received")
                        .correlateBy("orderId")
                        .then("complete")
                        .timeout(timeout -> timeout.after(Duration.ofHours(1)).goTo("failed")))
                .gateway("audit", gateway -> gateway.type(GatewayType.EXCLUSIVE).otherwise("complete"))
                .end("complete")
                .end("failed")
                .build();

        require("order.created".equals(definition.metadata().get("startEvent")), "Event start was not retained");
        require("orderId".equals(definition.metadata().get("correlateBy")), "Correlation field was not retained");
        require(definition.nodes().get("route").type() == WorkflowNodeType.GATEWAY,
                "Conditional branch was not compiled to a gateway");
        require(definition.nodes().get("start").retryPolicy().maxAttempts() == 3, "Retry was not retained");
        require(definition.nodes().get("fork").fork().branches().size() == 2, "Fork branches were not retained");
        require(definition.nodes().get("joined").join().requiredBranches().size() == 2,
                "Join requirements were not retained");
        require(definition.nodes().get("child").subWorkflow().inputMappings().containsKey("orderId"),
                "Sub-workflow input was not retained");
    }

    private static void rejectsInvalidDefinitionsBeforeRegistration() {
        expectValidation(() -> WorkflowDefinitionBuilder.workflow("missing-target")
                .version("1")
                .startAt("work")
                .step("work", step -> step.action("work.execute").onSuccess("absent"))
                .end("done")
                .build());
        expectValidation(() -> WorkflowDefinitionBuilder.workflow("missing-version")
                .startAt("done")
                .end("done")
                .build());
    }

    private static void expectValidation(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected WorkflowValidationException");
        } catch (WorkflowValidationException expected) {
            require(!expected.violations().isEmpty(), "Validation error did not contain a violation");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
