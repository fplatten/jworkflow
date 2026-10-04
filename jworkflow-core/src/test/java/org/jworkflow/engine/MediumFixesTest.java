package org.jworkflow.engine;
import org.jworkflow.internal.model.WorkflowTimer;
import org.jworkflow.internal.model.WorkflowDefinitionRegistry;
import org.jworkflow.internal.events.EventDeliveryFailure;
import org.jworkflow.internal.events.EventStatusRecordingErrorHandler;
import org.jworkflow.internal.events.EventStatusAttempt;
import org.jworkflow.internal.events.EventStatusRepository;
import org.jworkflow.internal.engine.WorkflowMutation;
import org.jworkflow.internal.engine.WorkflowStateMachine;
import org.jworkflow.internal.engine.InMemoryWorkflowEngine;

import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.definition.WorkflowDefinitionText;
import org.jworkflow.dsl.DslCompilationException;
import org.jworkflow.dsl.GroovyWorkflowDslCompiler;
import org.jworkflow.events.*;
import org.jworkflow.inbox.InboxMessage;
import org.jworkflow.inbox.InboxMessageStatus;
import org.jworkflow.model.*;
import org.jworkflow.security.FilteringEventCapturePolicy;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for declared-but-ignored features and engine/backend inconsistencies. */
class MediumFixesTest {

    @Test void unsupportedGatewayTypesAreRejected() {
        WorkflowDefinitionBuilder builder = WorkflowDefinitionBuilder.workflow("g").version("1").startAt("route")
                .gateway("route", g -> g.type(GatewayType.PARALLEL).otherwise("done")).end("done");
        assertTrue(assertThrows(WorkflowValidationException.class, builder::build).getMessage().contains("gateway.type.unsupported"));
    }

    @Test void timeoutWithoutTargetOrEventIsRejected() {
        WorkflowDefinitionBuilder builder = WorkflowDefinitionBuilder.workflow("t").version("1").startAt("approval")
                .waitFor("approval", w -> w.event("item.approved").correlateBy("businessKey").then("done")
                        .timeout(t -> t.after(Duration.ofHours(1))))
                .end("done");
        assertTrue(assertThrows(WorkflowValidationException.class, builder::build).getMessage().contains("timeout.action.required"));
    }

    @Test void emitOnlyTimeoutEmitsEventAndKeepsState() {
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        registry.register(WorkflowDefinitionBuilder.workflow("t").version("1").startAt("approval")
                .waitFor("approval", w -> w.event("item.approved").correlateBy("businessKey").then("done")
                        .timeout(t -> t.after(Duration.ofHours(1)).emit("approval.delayed")))
                .end("done").build());
        WorkflowStateMachine machine = new WorkflowStateMachine(registry, Map.of(), new BranchConditionEvaluator(), Map.of(), Map.of());
        WorkflowMutation started = machine.start(new StartWorkflowCommand("t", "1", "item-1", Map.of(), null)).mutation();
        WorkflowTimer timer = started.timersAfter().get(0);

        WorkflowMutation fired = machine.fireTimer(started.nextSnapshot(), started.timersAfter(), timer,
                Instant.now().plus(Duration.ofHours(2))).mutation();

        assertEquals("approval", fired.nextSnapshot().state(), "an emit-only timeout must not move the workflow");
        assertTrue(fired.events().stream().anyMatch(e -> e.eventName().value().equals("approval.delayed")));
    }

    @Test void durableAcceptanceMatchesInMemoryActionFallback() {
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        registry.register(WorkflowDefinitionBuilder.workflow("pay").version("1").startAt("charge")
                .step("charge", s -> s.action("payment.charge").onSuccess("done")).end("done").build());
        WorkflowStateMachine machine = new WorkflowStateMachine(registry, Map.of(), new BranchConditionEvaluator(), Map.of(), Map.of());
        WorkflowSnapshot atCharge = machine.start(new StartWorkflowCommand("pay", "1", "o-1", Map.of(), null)).mutation().nextSnapshot();
        assertTrue(machine.accepts(atCharge, WorkflowEvent.of("payment.charged")));
    }

    @Test void reenteredLoopGetsFullIterationBudget() throws Exception {
        WorkflowDefinition definition = WorkflowDefinitionBuilder.workflow("loops").version("1").startAt("begin")
                .step("begin", s -> s.action("noop").onSuccess("again"))
                .loop("again", l -> l.whileVariable("more", "eq", true).doStep("work").maxIterations(1).then("after"))
                .step("work", s -> s.action("noop").onSuccess("again"))
                .step("after", s -> s.action("noop").onSuccess("again").onFailure("done"))
                .end("done").build();
        try (WorkflowEngine engine = WorkflowEngine.builder().definition(definition)
                .stepHandler("noop", context -> StepResult.success()).build()) {
            WorkflowInstanceId id = engine.start("loops", "l-1", Map.of("more", true));
            for (String expected : List.of("work", "after", "work")) {
                engine.signal(id, new WorkflowSignal("loop.advanced", "corr", null, "l-1", Instant.now(), Map.of()));
                assertEquals(expected, engine.snapshot(id).state());
            }
        }
    }

    @Test void dslRejectsSlaUndeliveredTriggerAndUnknownStart() {
        GroovyWorkflowDslCompiler compiler = new GroovyWorkflowDslCompiler();
        String sla = """
                workflow("w") { version "1"; start at: "s"
                    step("s") { sla "PT1M", onBreach: "late"; then end("done") }
                    end("done") }
                """;
        assertThrows(DslCompilationException.class, () -> compiler.compile(new WorkflowDefinitionText("sla.groovy", sla)));
        String trigger = """
                workflow("w") { version "1"; start when: "order.created"
                    step("s") { on "order.shipped"; then end("done") }
                    end("done") }
                """;
        assertTrue(assertThrows(DslCompilationException.class, () -> compiler.compile(new WorkflowDefinitionText("on.groovy", trigger)))
                .getMessage().contains("order.shipped"));
        String typo = """
                workflow("w") { version "1"; start at: "sstep"
                    step("s") { then end("done") }
                    end("done") }
                """;
        DslCompilationException failure = assertThrows(DslCompilationException.class,
                () -> compiler.compile(new WorkflowDefinitionText("typo.groovy", typo)));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().equals("start.unknown")));
    }

    @Test void headerAndAttributeFiltersIgnoreCase() {
        EventMetadata metadata = new EventMetadata(null, new EventName("tax.submitted"), "payroll", "corr", null, null,
                null, null, null, "1", Instant.EPOCH, Instant.EPOCH, Map.of("authorization", "secret", "Trace", "keep"));
        WorkflowEvent event = new WorkflowEvent(metadata, new EventMessage(Map.of(), "application/json", null, null, false,
                Map.of("TOKEN", "secret")));
        WorkflowEvent filtered = new FilteringEventCapturePolicy(Set.of("Authorization"), Set.of("token"), false).filter(event);
        assertEquals(Map.of("Trace", "keep"), filtered.metadata().headers());
        assertTrue(filtered.message().attributes().isEmpty());
    }

    @Test void acceptedInboxMessageAlwaysStartsReceived() {
        InboxMessage supplied = new InboxMessage(null, "e-1", "erp", EventMessage.empty(), "corr", null, null,
                Instant.now(), InboxMessageStatus.PROCESSED, 4, null, "old", null, null);
        InboxMessage received = supplied.asReceived();
        assertEquals(InboxMessageStatus.RECEIVED, received.status());
        assertEquals(0, received.attemptCount());
        assertNull(received.processedAt());
        assertNull(received.lastError());
    }

    @Test void listenerFailureNumbersComeFromRecordedHistory() {
        List<EventStatusAttempt> stored = new CopyOnWriteArrayList<>();
        EventStatusRepository repository = new EventStatusRepository() {
            @Override public void append(EventStatusAttempt attempt) { stored.add(attempt); }
            @Override public List<EventStatusAttempt> findAttempts(UUID eventId) {
                return stored.stream().filter(a -> a.eventId().equals(eventId)).toList();
            }
        };
        EventStatusRecordingErrorHandler handler = new EventStatusRecordingErrorHandler(repository);
        WorkflowEvent event = WorkflowEvent.of("order.created");
        for (int i = 0; i < 2; i++) handler.handle(new EventDeliveryFailure("t", event, "sub", new IllegalStateException(), Instant.now()));
        assertEquals(List.of(1, 2), stored.stream().map(EventStatusAttempt::attemptNumber).toList());
    }

    @Test void finishedInstancesAreEvictedBeyondRetentionLimit() throws Exception {
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(WorkflowDefinition.of("quick", "1", "done", WorkflowNode.end("done"))).build()) {
            WorkflowInstanceId first = engine.start("quick", "q-0", Map.of());
            for (int i = 1; i <= InMemoryWorkflowEngine.MAX_RETAINED_FINISHED_INSTANCES; i++) {
                engine.start("quick", "q-" + i, Map.of());
            }
            assertThrows(WorkflowInstanceNotFoundException.class, () -> engine.snapshot(first));
        }
    }
}
