package org.jworkflow.engine;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Sub-workflow outcomes, manual retry, routing cycles and event-loop survival. */
class EngineCorrectnessTest {

    @Test void waitingChildTakesFailureRouteAndIsCanceled() throws Exception {
        WorkflowDefinition child = WorkflowDefinitionBuilder.workflow("child").version("1").startAt("approval")
                .waitFor("approval", wait -> wait.event("child.approved").correlateBy("businessKey").then("done"))
                .end("done").build();
        try (WorkflowEngine engine = engine(child, parent("child"))) {
            WorkflowInstanceId id = run(engine, "order-1");
            WorkflowSnapshot parent = engine.snapshot(id);
            assertEquals("failed", parent.state(), "a child that is still waiting must not count as success");
            assertEquals("RUNNING", parent.variables().get("__jworkflow.subWorkflow.call.status"));
            WorkflowInstanceId childId = WorkflowInstanceId.fromString(
                    (String) parent.variables().get("__jworkflow.subWorkflow.call.instanceId"));
            assertEquals(WorkflowStatus.CANCELED, engine.snapshot(childId).status());
        }
    }

    @Test void completedChildTakesSuccessRoute() throws Exception {
        WorkflowDefinition child = WorkflowDefinitionBuilder.workflow("child").version("1").startAt("done").end("done").build();
        try (WorkflowEngine engine = engine(child, parent("child"))) {
            WorkflowInstanceId id = run(engine, "order-2");
            assertEquals("ok", engine.snapshot(id).state());
        }
    }

    @Test void selfCallingWorkflowStopsAtDepthLimit() throws Exception {
        try (WorkflowEngine engine = engine(parent("parent"))) {
            WorkflowInstanceId id = assertDoesNotThrow(() -> run(engine, "order-3"));
            assertEquals(WorkflowStatus.COMPLETED, engine.snapshot(id).status());
        }
    }

    @Test void manualRetryRunsStepWhoseRetryPolicyIsExhausted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WorkflowDefinition definition = WorkflowDefinitionBuilder.workflow("retry").version("1").startAt("charge")
                .step("charge", step -> step.action("charge")
                        .retry(retry -> retry.maxAttempts(1).backoff(Duration.ZERO)).onSuccess("done"))
                .end("done").build();
        try (WorkflowEngine engine = WorkflowEngine.builder().definition(definition)
                .stepHandler("charge", context -> calls.incrementAndGet() == 1
                        ? StepResult.failure("declined", "first attempt fails") : StepResult.success())
                .build()) {
            WorkflowInstanceId id = engine.start("retry", "order-4", Map.of());
            engine.signal(id, new WorkflowSignal("charge.requested", "corr", null, "order-4", Instant.now(), Map.of()));
            assertEquals(WorkflowStatus.FAILED, engine.snapshot(id).status());

            WorkflowCommandResult result = engine.retryFailedStep(new RetryFailedStepCommand(id, "charge", null));

            assertEquals(WorkflowStatus.COMPLETED, result.snapshot().status());
            assertEquals(2, calls.get());
        }
    }

    @Test void gatewayOnlyCycleIsRejected() {
        WorkflowDefinitionBuilder builder = WorkflowDefinitionBuilder.workflow("cycle").version("1").startAt("a")
                .gateway("a", g -> g.when(b -> b.variable("go", "eq", true).goTo("done")).otherwise("b"))
                .gateway("b", g -> g.otherwise("a"))
                .end("done");
        WorkflowValidationException failure = assertThrows(WorkflowValidationException.class, builder::build);
        assertTrue(failure.getMessage().contains("routing.cycle"), failure.getMessage());
    }

    @Test void loopBoundedCycleIsStillValid() {
        assertDoesNotThrow(() -> WorkflowDefinitionBuilder.workflow("bounded").version("1").startAt("again")
                .loop("again", loop -> loop.whileVariable("more", "eq", true).doStep("route").maxIterations(3).then("done"))
                .gateway("route", g -> g.otherwise("again"))
                .end("done").build());
    }

    @Test void eventLoopSurvivesJvmError() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        List<String> received = new CopyOnWriteArrayList<>();
        try (WorkflowEngine engine = WorkflowEngine.builder().eventPublisher(event -> {
            if (!event.eventName().value().equals("event.received")) return;
            if (observations.incrementAndGet() == 1) throw new NoClassDefFoundError("injected");
            received.add(event.metadata().headers().get("eventName"));
        }).build()) {
            engine.publish(WorkflowEvent.of("alpha.received"));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (observations.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            engine.publish(WorkflowEvent.of("beta.received"));
            deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (received.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(List.of("beta.received"), received, "the loop must keep processing after an Error");
        }
    }

    private static WorkflowEngine engine(WorkflowDefinition... definitions) throws Exception {
        WorkflowEngineBuilder builder = WorkflowEngine.builder().stepHandler("noop", context -> StepResult.success());
        for (WorkflowDefinition definition : definitions) builder.definition(definition);
        return builder.build();
    }

    /** Starts the parent and runs its first step, which routes into the sub-workflow call. */
    private static WorkflowInstanceId run(WorkflowEngine engine, String businessKey) {
        WorkflowInstanceId id = engine.start("parent", businessKey, Map.of());
        engine.signal(id, new WorkflowSignal("begin.requested", "corr", null, businessKey, Instant.now(), Map.of()));
        return id;
    }

    private static WorkflowDefinition parent(String childName) {
        return WorkflowDefinitionBuilder.workflow("parent").version("1").startAt("begin")
                .step("begin", step -> step.action("noop").onSuccess("call"))
                .subWorkflow("call", call -> call.workflow(childName, "1")
                        .onSuccess("child.succeeded", "ok").onFailure("child.failed", "failed"))
                .end("ok").end("failed").build();
    }
}
