package org.jworkflow.engine;

import org.jworkflow.events.EventMessage;
import org.jworkflow.events.EventMetadata;
import org.jworkflow.events.EventName;
import org.jworkflow.events.EventStatusAttempt;
import org.jworkflow.events.EventStatusRepository;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.BranchConditionEvaluator;
import org.jworkflow.model.DefinitionValidator;
import org.jworkflow.model.GatewayType;
import org.jworkflow.model.RetryPolicy;
import org.jworkflow.model.TimeoutDefinition;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowDefinitionRegistry;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.model.WorkflowNodeType;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.model.WorkflowTimer;
import org.jworkflow.model.WorkflowTransition;
import org.jworkflow.persistence.WorkflowDefinitionRepository;
import org.jworkflow.persistence.WorkflowEventRepository;
import org.jworkflow.persistence.WorkflowInstanceRepository;
import org.jworkflow.persistence.WorkflowPersistence;
import org.jworkflow.persistence.WorkflowTimerRepository;
import org.jworkflow.persistence.WorkflowTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class CoreRuntimeContractTest {
    public static void main(String[] args) throws Exception {
        routesDeclaredCorrelationToEveryMatchingWorkflow();
        pinsDefinitionVersionForRunningInstances();
        serializesConcurrentIdempotentStarts();
        enforcesTerminalCancelAndResumeSemantics();
        executesRetryBackoffAndTimeoutTimers();
        persistsStateEventsTimersAndFailures();
        defensivelyCopiesEventsAndValidatesDefinitions();
        contextAwareExecutorRestoresAndClearsContext();
    }

    private static void contextAwareExecutorRestoresAndClearsContext() throws Exception {
        WorkflowExecutionContext context = new WorkflowExecutionContext(WorkflowInstanceId.random(), "context-flow", "1",
                "step", WorkflowEvent.of("work.started"), "corr", "business", "cause", "trace");
        try (WorkflowEngine engine = InMemoryWorkflowEngine.createDefault()) {
            ExecutorService delegate = Executors.newSingleThreadExecutor();
            try (WorkflowExecutionContext.Scope ignored = WorkflowExecutionContext.bind(context)) {
                java.util.concurrent.CompletableFuture<String> captured = new java.util.concurrent.CompletableFuture<>();
                engine.contextAwareExecutor(delegate).execute(() -> captured.complete(
                        WorkflowExecutionContext.current().orElseThrow().traceId()));
                require("trace".equals(captured.get()), "captured workflow context was not restored in asynchronous work");
            } finally {
                Future<Boolean> cleared = delegate.submit(() -> WorkflowExecutionContext.current().isEmpty());
                require(cleared.get(), "workflow context leaked after wrapped asynchronous work");
                delegate.shutdownNow();
            }
        }
    }

    private static void routesDeclaredCorrelationToEveryMatchingWorkflow() throws Exception {
        WorkflowDefinition first = eventStartedDefinition("customer-a", "1", "customer.created", "customerId");
        WorkflowDefinition second = eventStartedDefinition("customer-b", "1", "customer.created", "customerId");
        AtomicInteger executions = new AtomicInteger();
        RecordingPersistence persistence = new RecordingPersistence();
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(first)
                .definition(second)
                .stepHandler("customer.process", context -> {
                    executions.incrementAndGet();
                    return StepResult.success();
                })
                .persistence(persistence)
                .build()) {
            engine.publish(WorkflowEvent.named("customer.created", Map.of("customerId", "customer-42")));
            await(() -> !persistence.statuses.isEmpty() || (engine.context().getWorkflows().size() == 2
                    && engine.context().getWorkflows().stream()
                    .allMatch(snapshot -> snapshot.status() == WorkflowStatus.COMPLETED)));
            require(persistence.statuses.isEmpty(), "Routing failed: "
                    + persistence.statuses.stream().map(EventStatusAttempt::lastErrorMessage).toList());
            Set<String> keys = engine.context().getWorkflows().stream()
                    .map(WorkflowSnapshot::businessKey)
                    .collect(java.util.stream.Collectors.toSet());
            require(keys.equals(Set.of("customer-42")), "Declared payload correlation key was not used");
            require(executions.get() == 2, "Start event was not routed to every matching workflow");
        }
    }

    private static void pinsDefinitionVersionForRunningInstances() {
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        registry.register(versionedDefinition("versioned", "1.0.0", "done-v1"));
        registry.register(versionedDefinition("semantic-order", "2.0.0", "done-2"));
        registry.register(versionedDefinition("semantic-order", "10.0.0", "done-10"));
        require("10.0.0".equals(registry.latest("semantic-order").orElseThrow().version()),
                "Latest definition used lexicographic version ordering");
        try (InMemoryWorkflowEngine engine = InMemoryWorkflowEngine.create(registry)) {
            WorkflowSnapshot started = engine.start(new StartWorkflowCommand(
                    "versioned", "1.0.0", "business-1", Map.of(), null)).snapshot();
            registry.register(versionedDefinition("versioned", "2.0.0", "done-v2"));
            engine.signal(started.instanceId(), new org.jworkflow.model.WorkflowSignal(
                    "work.completed", "correlation-1", null, "business-1", Instant.now(), Map.of()));
            WorkflowSnapshot completed = engine.snapshot(started.instanceId());
            require("1.0.0".equals(completed.workflowVersion()), "Snapshot did not retain its definition version");
            require("done-v1".equals(completed.state()), "Running instance switched to a newer definition");
        }
    }

    private static void serializesConcurrentIdempotentStarts() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "atomic-start", "1", "done", WorkflowNode.end("done"));
        try (WorkflowEngine engine = WorkflowEngine.builder().definition(definition).build()) {
            WorkflowCommandMetadata metadata = new WorkflowCommandMetadata(
                    null, "same-key", "atomic-start", "1", null, "business-1",
                    null, null, null, null, "test", null, null, Map.of());
            StartWorkflowCommand command = new StartWorkflowCommand(
                    "atomic-start", "1", "business-1", Map.of(), metadata);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<StartWorkflowResult> first = executor.submit(() -> { start.await(); return engine.start(command); });
                Future<StartWorkflowResult> second = executor.submit(() -> { start.await(); return engine.start(command); });
                start.countDown();
                StartWorkflowResult left = first.get();
                StartWorkflowResult right = second.get();
                require(left.workflowInstanceId().equals(right.workflowInstanceId()),
                        "Concurrent idempotent starts returned different instances");
                require(engine.context().getWorkflows().size() == 1,
                        "Concurrent idempotent starts created duplicate instances");
                require(left.idempotentRepeat() != right.idempotentRepeat(),
                        "Exactly one concurrent result should be the idempotent repeat");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void enforcesTerminalCancelAndResumeSemantics() throws Exception {
        WorkflowDefinition terminal = WorkflowDefinition.of("terminal", "1", "done", WorkflowNode.end("done"));
        AtomicInteger attempts = new AtomicInteger();
        WorkflowDefinition resumable = versionedDefinition("resumable", "1", "done");
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(terminal)
                .definition(resumable)
                .stepHandler("work.complete", context -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("first attempt");
                    return StepResult.success();
                })
                .build()) {
            WorkflowInstanceId terminalId = engine.start("terminal", "terminal-1", Map.of());
            WorkflowCommandResult canceled = engine.cancel(new CancelWorkflowCommand(terminalId, null));
            require(canceled.status() == WorkflowCommandStatus.NO_OP, "Completed workflow was canceled");
            require(engine.snapshot(terminalId).status() == WorkflowStatus.COMPLETED,
                    "Cancel rewrote terminal workflow state");

            WorkflowInstanceId resumableId = engine.start("resumable", "resume-1", Map.of());
            engine.signal(resumableId, new org.jworkflow.model.WorkflowSignal(
                    "work.requested", "correlation-2", null, "resume-1", Instant.now(), Map.of()));
            require(engine.snapshot(resumableId).status() == WorkflowStatus.FAILED, "Expected failed workflow");
            WorkflowCommandResult resumed = engine.resume(new ResumeWorkflowCommand(resumableId, null));
            require(resumed.status() == WorkflowCommandStatus.ACCEPTED, "Failed workflow was not resumed");
            require(resumed.snapshot().status() == WorkflowStatus.COMPLETED, "Resumed workflow did not continue");
        }
    }

    private static void executesRetryBackoffAndTimeoutTimers() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowNode retryStep = new WorkflowNode(
                "retry", WorkflowNodeType.STEP, "retry.execute", null, null,
                null, null, null, null, null, null,
                new RetryPolicy(2, Duration.ofMillis(25)), null,
                List.of(new WorkflowTransition("success", "done", null, null)));
        WorkflowDefinition retryDefinition = WorkflowDefinition.of(
                "retry-contract", "1", "retry", retryStep, WorkflowNode.end("done"));
        WorkflowNode timeoutStep = new WorkflowNode(
                "waiting", WorkflowNodeType.STEP, "wait.execute", null, null,
                null, null, null, null, null, null, null,
                new TimeoutDefinition(Duration.ofMillis(25), "timed-out", null), List.of());
        WorkflowDefinition timeoutDefinition = WorkflowDefinition.of(
                "timeout-contract", "1", "waiting", timeoutStep, WorkflowNode.end("timed-out"));

        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(retryDefinition)
                .definition(timeoutDefinition)
                .stepHandler("retry.execute", context -> attempts.incrementAndGet() == 1
                        ? StepResult.failure("temporary", "retry") : StepResult.success())
                .build()) {
            WorkflowInstanceId retryId = engine.start("retry-contract", "retry-1", Map.of());
            engine.signal(retryId, new org.jworkflow.model.WorkflowSignal(
                    "retry.requested", "correlation-3", null, "retry-1", Instant.now(), Map.of()));
            await(() -> engine.snapshot(retryId).status() == WorkflowStatus.COMPLETED);
            require(attempts.get() == 2, "Retry policy did not execute exactly two attempts");

            WorkflowInstanceId timeoutId = engine.start("timeout-contract", "timeout-1", Map.of());
            await(() -> engine.snapshot(timeoutId).status() == WorkflowStatus.COMPLETED);
            require("timed-out".equals(engine.snapshot(timeoutId).state()), "Timeout did not route to target");
            require(((InMemoryWorkflowEngine) engine).pendingTimers().isEmpty(), "Completed timers remained pending");
        }
    }

    private static void persistsStateEventsTimersAndFailures() throws Exception {
        RecordingPersistence persistence = new RecordingPersistence();
        ThrowingListener listener = new ThrowingListener();
        WorkflowNode node = new WorkflowNode(
                "receive", WorkflowNodeType.STEP, "thing.process", "thrower", "onEvent",
                null, null, null, null, null, null, null,
                new TimeoutDefinition(Duration.ofSeconds(1), "done", null),
                List.of(new WorkflowTransition("success", "done", null, new EventName("thing.processed"))));
        WorkflowDefinition definition = new WorkflowDefinition(
                "persistent", "1", "receive", Map.of("receive", node, "done", WorkflowNode.end("done")),
                Map.of("startEvent", "thing.created", "correlateBy", "thingId"));
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .listener("thrower", listener)
                .persistence(persistence)
                .build()) {
            engine.publish(WorkflowEvent.named("thing.created", Map.of("thingId", "thing-1")));
            await(() -> !persistence.statuses.isEmpty());
            require(!persistence.snapshots.isEmpty(), "State changes were not persisted");
            require(!persistence.events.isEmpty(), "Runtime events were not persisted");
            require(!persistence.timers.isEmpty(), "Timers were not persisted");
            require(!persistence.statuses.isEmpty(), "Event handling failure was not recorded");
            require(persistence.transactions.get() > 0, "Persistence writes did not use transaction boundaries");
        }
    }

    @SuppressWarnings("unchecked")
    private static void defensivelyCopiesEventsAndValidatesDefinitions() throws Exception {
        ArrayList<String> mutableList = new ArrayList<>(List.of("before"));
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", mutableList);
        WorkflowEvent event = WorkflowEvent.named("payload.created", payload);
        mutableList.add("after");
        payload.put("new", true);
        Map<String, Object> copied = (Map<String, Object>) event.message().payload();
        require(!copied.containsKey("new"), "Event payload map was not copied");
        require(((List<?>) copied.get("items")).equals(List.of("before")), "Nested event payload was not copied");
        expect(UnsupportedOperationException.class, () -> ((List<Object>) copied.get("items")).add("mutation"));

        WorkflowNode invalidGateway = new WorkflowNode(
                "route", WorkflowNodeType.GATEWAY, null, null, null,
                null, null, null, GatewayType.EXCLUSIVE, null, null, null, null, List.of());
        WorkflowDefinition invalid = new WorkflowDefinition(
                "invalid", "1", "route", Map.of("route", invalidGateway, "done", WorkflowNode.end("done")), Map.of());
        require(!new DefinitionValidator().validate(invalid).valid(), "Invalid gateway passed validation");
        expect(WorkflowValidationException.class, () -> WorkflowEngine.builder().definition(invalid));

        WorkflowDefinition first = WorkflowDefinition.of("checksum", "1", "done", WorkflowNode.end("done"));
        WorkflowDefinition second = new WorkflowDefinition(
                "checksum", "1", "done", first.nodes(), Map.of("changed", "true"));
        require(!first.checksum().equals(second.checksum()), "Definition checksum ignored structural metadata");
    }

    private static WorkflowDefinition eventStartedDefinition(
            String name, String version, String startEvent, String correlateBy
    ) {
        WorkflowNode step = WorkflowNode.step("process", "customer.process",
                List.of(new WorkflowTransition("success", "done", null, null)));
        return new WorkflowDefinition(name, version, "process",
                Map.of("process", step, "done", WorkflowNode.end("done")),
                Map.of("startEvent", startEvent, "correlateBy", correlateBy));
    }

    private static WorkflowDefinition versionedDefinition(String name, String version, String terminal) {
        WorkflowNode step = WorkflowNode.step("work", "work.complete",
                List.of(new WorkflowTransition("success", terminal, null, new EventName("work.completed"))));
        return WorkflowDefinition.of(name, version, "work", step, WorkflowNode.end(terminal));
    }

    private static void await(CheckedBoolean condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (condition.get()) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    @FunctionalInterface
    private interface CheckedBoolean { boolean get() throws Exception; }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    public static final class ThrowingListener {
        public void onEvent(WorkflowEvent event) {
            throw new IllegalStateException("listener failure");
        }
    }

    private static final class RecordingPersistence implements WorkflowPersistence {
        private final List<WorkflowSnapshot> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<WorkflowEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<WorkflowTimer> timers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<EventStatusAttempt> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger transactions = new AtomicInteger();

        @Override public WorkflowDefinitionRepository definitions() {
            return new WorkflowDefinitionRepository() {
                @Override public void save(WorkflowDefinition definition) { }
                @Override public Optional<WorkflowDefinition> find(String name, String version) { return Optional.empty(); }
                @Override public Optional<WorkflowDefinition> findRevision(String name, String version, String revision) { return Optional.empty(); }
                @Override public List<WorkflowDefinition> findAll() { return List.of(); }
            };
        }
        @Override public WorkflowInstanceRepository instances() {
            return new WorkflowInstanceRepository() {
                @Override public void insert(WorkflowSnapshot snapshot) { snapshots.add(snapshot); }
                @Override public WorkflowSnapshot update(WorkflowSnapshot snapshot, long expected) { snapshots.add(snapshot); return snapshot; }
                @Override public Optional<WorkflowSnapshot> findById(WorkflowInstanceId id) { return Optional.empty(); }
                @Override public Optional<WorkflowSnapshot> findByCorrelationId(String id) { return Optional.empty(); }
                @Override public List<WorkflowSnapshot> findActive(int limit) { return List.of(); }
            };
        }
        @Override public WorkflowEventRepository events() {
            return new WorkflowEventRepository() {
                @Override public void append(WorkflowEvent event) { events.add(event); }
                @Override public Optional<WorkflowEvent> find(UUID id) { return Optional.empty(); }
                @Override public List<WorkflowEvent> findByWorkflowInstance(WorkflowInstanceId id) { return List.copyOf(events); }
            };
        }
        @Override public EventStatusRepository eventStatuses() {
            return new EventStatusRepository() {
                @Override public void append(EventStatusAttempt attempt) { statuses.add(attempt); }
                @Override public List<EventStatusAttempt> findAttempts(UUID eventId) { return List.copyOf(statuses); }
            };
        }
        @Override public WorkflowTimerRepository timers() {
            return new WorkflowTimerRepository() {
                @Override public void save(WorkflowTimer timer) { timers.add(timer); }
                @Override public List<WorkflowTimer> dueTimers(Instant now) { return List.of(); }
                @Override public List<WorkflowTimer> claimDue(Instant now, String owner, Instant until, int limit) { return List.of(); }
                @Override public void markFired(UUID id, String owner, Instant at) { }
                @Override public void markFailed(UUID id, String owner, String error, Instant next) { }
                @Override public void cancel(UUID id, Instant at) { }
                @Override public int releaseExpiredClaims(Instant now) { return 0; }
                @Override public void appendAttempt(org.jworkflow.model.WorkflowTimerAttempt attempt) { }
                @Override public List<org.jworkflow.model.WorkflowTimerAttempt> findAttempts(UUID timerId) { return List.of(); }
            };
        }
        @Override public WorkflowTransactionManager transactions() {
            return transaction -> { transactions.incrementAndGet(); transaction.execute(); };
        }
    }
}
