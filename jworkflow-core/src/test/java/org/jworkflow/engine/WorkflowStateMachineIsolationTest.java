package org.jworkflow.engine;
import org.jworkflow.internal.engine.WorkflowTransitionResult;
import org.jworkflow.internal.engine.WorkflowStateMachine;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.model.BranchConditionEvaluator;
import org.jworkflow.internal.model.WorkflowDefinitionRegistry;
import org.jworkflow.model.WorkflowSignal;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Transition calculations must return timers as data and never fire them in the background.
 */
class WorkflowStateMachineIsolationTest {

    @Test
    void scheduledRetryIsNotFiredByTransientCalculatorEngine() throws Exception {
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        registry.register(WorkflowDefinitionBuilder.workflow("payment")
                .version("1")
                .startAt("charge")
                .step("charge", step -> step
                        .action("payment.charge")
                        .retry(retry -> retry.maxAttempts(3).backoff(Duration.ofMillis(50)))
                        .onSuccess("done"))
                .end("done")
                .build());
        AtomicInteger charges = new AtomicInteger();
        StepHandler failingCharge = context -> {
            charges.incrementAndGet();
            return StepResult.failure("declined", "card declined");
        };
        WorkflowStateMachine machine = new WorkflowStateMachine(registry, Map.of("payment.charge", failingCharge),
                new BranchConditionEvaluator(), Map.of(), Map.of());

        WorkflowTransitionResult<StartWorkflowResult> started = machine.start(
                new StartWorkflowCommand("payment", "1", "order-1", Map.of(), null));

        WorkflowSnapshot current = started.mutation().nextSnapshot();
        WorkflowTransitionResult<WorkflowCommandResult> signaled = machine.signal(current, List.of(),
                new SignalWorkflowCommand(current.instanceId(), new WorkflowSignal("payment.requested", "corr-1", null,
                        "order-1", Instant.now(), Map.of()), null));

        assertEquals(1, charges.get(), "Signal must run the step once");
        assertEquals(WorkflowStatus.WAITING, signaled.commandResult().snapshot().status());
        assertFalse(signaled.mutation().timersAfter().isEmpty(), "Retry timer must be returned as data");
        int chargesAfterCalculation = charges.get();
        Thread.sleep(400);
        assertEquals(chargesAfterCalculation, charges.get(),
                "Calculator engine fired the retry timer in the background");
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(thread -> thread.isAlive() && "jworkflow-events".equals(thread.getName())),
                "Calculator engine left a background event thread running");
    }
}
