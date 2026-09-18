package org.jworkflow.engine;

import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import org.jworkflow.security.*;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class InMemoryWorkflowEngine implements WorkflowEngine {
    private static final String TEXT_EVENT_CORRELATED = "event.correlated";
    private static final String TEXT_COMMAND = "command";
    private static final String TEXT_START = "start";
    private static final String TEXT_SIGNAL = "signal";
    private static final String TEXT_RETRY_FAILED_STEP = "retryFailedStep";
    private static final String TEXT_CANCEL = "cancel";
    private static final String TEXT_RESUME = "resume";
    private static final String TEXT_WORKFLOW_COMPLETED = "workflow.completed";
    private static final String TEXT_ACTION = "action";
    private static final String TEXT_SUCCESS = "success";
    private static final String TEXT_TRANSITION_TAKEN = "transition.taken";
    private static final String TEXT_ATTEMPT = "attempt";
    private static final String TEXT_JWORKFLOW = "jworkflow";
    private static final String TEXT_WORKFLOW_KEY = "workflowKey";
    private static final String TEXT_WORKFLOW_VERSION = "workflowVersion";
    private static final String TEXT_STATE = "state";
    private static final String TEXT_BUSINESS_KEY = "businessKey";
    private final Map<WorkflowInstanceId, WorkflowSnapshot> instances = new ConcurrentHashMap<>();
    private final Map<String, WorkflowInstanceId> instancesByBusinessKey = new ConcurrentHashMap<>();
    private final Map<String, IdempotentResult> idempotentResults = new ConcurrentHashMap<>();
    private final Map<String, Object> idempotencyLocks = new ConcurrentHashMap<>();
    private final Map<WorkflowInstanceId, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();
    private final WorkflowDefinitionRegistry definitions;
    private final EventPublisher eventPublisher;
    private final WorkflowPersistence persistence;
    private final Map<String, StepHandler> stepHandlers;
    private final BranchConditionEvaluator branchConditionEvaluator;
    private final CopyOnWriteArrayList<WorkflowTimer> timers = new CopyOnWriteArrayList<>();
    private final Map<String, String> workflowStartEvents;
    private final Map<String, Object> listenerInstances;
    private final BlockingQueue<WorkflowEvent> incomingEvents = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final WorkflowEngineContext context = new InMemoryWorkflowEngineContext();
    private final AtomicReference<ExecutorService> eventExecutor = new AtomicReference<>();
    private final Clock clock;
    private final EventCapturePolicy eventCapturePolicy;

    @SuppressWarnings("java:S107") // Internal composition root; clients use scoped factories or the builder.
    private InMemoryWorkflowEngine(
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers,
            BranchConditionEvaluator branchConditionEvaluator,
            Map<String, String> workflowStartEvents,
            Map<String, Object> listenerInstances,
            WorkflowPersistence persistence,
            Clock clock,
        EventCapturePolicy eventCapturePolicy
    ) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        EventPublisher configuredPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.eventCapturePolicy = Objects.requireNonNull(eventCapturePolicy, "eventCapturePolicy");
        this.eventPublisher = event -> configuredPublisher.publish(
                SafeEventCapturePolicy.filter(this.eventCapturePolicy, event));
        this.persistence = persistence;
        this.stepHandlers = new ConcurrentHashMap<>(stepHandlers == null ? Map.of() : stepHandlers);
        this.branchConditionEvaluator = Objects.requireNonNull(branchConditionEvaluator, "branchConditionEvaluator");
        this.workflowStartEvents = new ConcurrentHashMap<>(workflowStartEvents == null ? Map.of() : workflowStartEvents);
        this.listenerInstances = new ConcurrentHashMap<>(listenerInstances == null ? Map.of() : listenerInstances);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static InMemoryWorkflowEngine createDefault() {
        return new InMemoryWorkflowEngine(
                new WorkflowDefinitionRegistry(),
                NoOpEventPublisher.INSTANCE,
                Map.of(),
                new BranchConditionEvaluator(),
                Map.of(),
                Map.of(),
                null, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    public static InMemoryWorkflowEngine create(WorkflowDefinitionRegistry definitions) {
        return new InMemoryWorkflowEngine(definitions, NoOpEventPublisher.INSTANCE, Map.of(), new BranchConditionEvaluator(), Map.of(), Map.of(), null, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    public static InMemoryWorkflowEngine create(WorkflowDefinitionRegistry definitions, EventPublisher eventPublisher) {
        return new InMemoryWorkflowEngine(definitions, eventPublisher, Map.of(), new BranchConditionEvaluator(), Map.of(), Map.of(), null, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    public static InMemoryWorkflowEngine create(
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers
    ) {
        return new InMemoryWorkflowEngine(definitions, eventPublisher, stepHandlers, new BranchConditionEvaluator(), Map.of(), Map.of(), null, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    public static InMemoryWorkflowEngine create(
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers,
            BranchConditionEvaluator branchConditionEvaluator
    ) {
        return new InMemoryWorkflowEngine(definitions, eventPublisher, stepHandlers, branchConditionEvaluator, Map.of(), Map.of(), null, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    public static InMemoryWorkflowEngine create(
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers,
            BranchConditionEvaluator branchConditionEvaluator,
            Map<String, String> workflowStartEvents,
            Map<String, Object> listenerInstances
    ) {
        return new InMemoryWorkflowEngine(definitions, eventPublisher, stepHandlers, branchConditionEvaluator, workflowStartEvents, listenerInstances, null, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    public static InMemoryWorkflowEngine create(
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers,
            BranchConditionEvaluator branchConditionEvaluator,
            Map<String, String> workflowStartEvents,
            Map<String, Object> listenerInstances,
            WorkflowPersistence persistence
    ) {
        return new InMemoryWorkflowEngine(definitions, eventPublisher, stepHandlers, branchConditionEvaluator,
                workflowStartEvents, listenerInstances, persistence, Clock.systemUTC(), CaptureAllEventPolicy.INSTANCE);
    }

    @SuppressWarnings("java:S107") // Compatibility factory retained for existing clients.
    public static InMemoryWorkflowEngine create(WorkflowDefinitionRegistry definitions, EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers, BranchConditionEvaluator branchConditionEvaluator,
            Map<String, String> workflowStartEvents, Map<String, Object> listenerInstances,
            WorkflowPersistence persistence, EventCapturePolicy eventCapturePolicy) {
        return new InMemoryWorkflowEngine(definitions, eventPublisher, stepHandlers, branchConditionEvaluator,
                workflowStartEvents, listenerInstances, persistence, Clock.systemUTC(), eventCapturePolicy);
    }

    static InMemoryWorkflowEngine create(WorkflowDefinitionRegistry definitions, EventPublisher publisher,
            Map<String, StepHandler> handlers, BranchConditionEvaluator conditions, Map<String, String> starts,
            Map<String, Object> listeners, Clock clock) {
        return new InMemoryWorkflowEngine(definitions, publisher, handlers, conditions, starts, listeners, null, clock, CaptureAllEventPolicy.INSTANCE);
    }

    @Override
    public void publish(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");
        if (!running.get()) {
            throw new WorkflowInvalidStateException("Workflow engine is closed");
        }
        incomingEvents.add(SafeEventCapturePolicy.filter(eventCapturePolicy, enrichFromCurrentContext(event)));
        ensureEventLoopStarted();
    }

    @Override
    public void registerListener(String listenerId, Object listener) {
        if (listenerId == null || listenerId.isBlank()) {
            throw new IllegalArgumentException("listenerId is required");
        }
        listenerInstances.put(listenerId, Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public Executor contextAwareExecutor(Executor delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return command -> delegate.execute(WorkflowExecutionContext.capture().wrap(command));
    }

    @Override
    public WorkflowEngineContext context() {
        return context;
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            ExecutorService executor = eventExecutor.get();
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    private void ensureEventLoopStarted() {
        if (eventExecutor.get() != null) {
            return;
        }
        synchronized (this) {
            if (eventExecutor.get() == null) {
                ExecutorService executor = Executors.newSingleThreadExecutor(new WorkflowThreadFactory());
                eventExecutor.set(executor);
                executor.execute(this::eventLoop);
            }
        }
    }

    @SuppressWarnings("java:S3776") // Lifecycle, interruption, and timer polling form one event loop.
    private void eventLoop() {
        int idlePolls = 0;
        while (running.get()) {
            WorkflowEvent event = null;
            try {
                event = incomingEvents.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    idlePolls = 0;
                    processPublishedEvent(event);
                } else if (pendingTimers().isEmpty() && ++idlePolls >= 10) {
                    synchronized (this) {
                        if (incomingEvents.isEmpty() && pendingTimers().isEmpty()) {
                            ExecutorService executor = eventExecutor.getAndSet(null);
                            if (executor != null) {
                                executor.shutdown();
                            }
                            return;
                        }
                    }
                }
                fireDueTimers(clock.instant());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                synchronized (this) {
                    ExecutorService executor = eventExecutor.getAndSet(null);
                    if (executor != null) {
                        executor.shutdown();
                    }
                }
                return;
            } catch (Exception exception) {
                if (event != null && persistence != null) {
                    WorkflowEvent failedEvent = event;
                    persistence.transactions().execute(() -> persistence.eventStatuses().append(
                            EventStatusAttempt.listenerFailure(failedEvent, "workflow-engine", 1, exception)));
                }
            }
        }
    }

    private void processPublishedEvent(WorkflowEvent event) {
        eventPublisher.publish(incomingObservation("event.received", event, null));
        java.util.HashSet<WorkflowInstanceId> startedInstances = new java.util.HashSet<>();
        for (WorkflowDefinition definition : startWorkflowsFor(event.eventName().value())) {
            String businessKey = correlationValue(definition, event);
            WorkflowInstanceId existing = instancesByBusinessKey.get(instanceKey(definition.name(), businessKey));
            if (existing == null) {
                StartWorkflowResult result = start(new StartWorkflowCommand(
                        definition.name(),
                        definition.version(),
                        businessKey,
                        eventVariables(event, businessKey),
                        new WorkflowCommandMetadata(
                                null, event.metadata().eventId() + ":" + definition.key(), definition.name(), definition.version(),
                                null, businessKey, eventCorrelationId(event),
                                event.metadata().eventId().toString(), event.metadata().traceId(),
                                event.metadata().tenantId(), event.metadata().sourceSystem(), null,
                                event.metadata().receivedAt(), event.metadata().headers())));
                WorkflowInstanceId started = result.workflowInstanceId();
                startedInstances.add(started);
                signal(started, event);
                eventPublisher.publish(incomingObservation(TEXT_EVENT_CORRELATED, event, instances.get(started)));
            }
        }

        WorkflowInstanceId explicitInstance = event.metadata().workflowInstanceId();
        if (explicitInstance != null && !startedInstances.contains(explicitInstance)) {
            WorkflowSnapshot snapshot = instances.get(explicitInstance);
            if (snapshot != null && acceptsEvent(snapshot, event)) {
                signal(explicitInstance, event);
                eventPublisher.publish(incomingObservation(TEXT_EVENT_CORRELATED, event, snapshot));
            } else {
                eventPublisher.publish(incomingObservation("event.ignored", event, snapshot));
            }
            return;
        }

        List<WorkflowInstanceId> targets = instances.values().stream()
                .filter(snapshot -> !startedInstances.contains(snapshot.instanceId()))
                .filter(snapshot -> snapshot.status() == WorkflowStatus.RUNNING || snapshot.status() == WorkflowStatus.WAITING)
                .filter(snapshot -> acceptsEvent(snapshot, event))
                .filter(snapshot -> snapshot.businessKey().equals(correlationValue(snapshot, event)))
                .sorted(java.util.Comparator
                        .comparing(WorkflowSnapshot::workflowKey)
                        .thenComparing(WorkflowSnapshot::workflowVersion)
                        .thenComparing(snapshot -> snapshot.instanceId().toString()))
                .map(WorkflowSnapshot::instanceId)
                .toList();
        targets.forEach(instanceId -> {
            signal(instanceId, event);
            eventPublisher.publish(incomingObservation(TEXT_EVENT_CORRELATED, event, instances.get(instanceId)));
        });
        if (startedInstances.isEmpty() && targets.isEmpty()) {
            eventPublisher.publish(incomingObservation("event.ignored", event, null));
        }
    }

    private List<WorkflowDefinition> startWorkflowsFor(String eventName) {
        String configured = workflowStartEvents.get(eventName);
        return definitions.snapshot().values().stream()
                .filter(definition -> eventName.equals(definition.metadata().get("startEvent"))
                        || definition.name().equals(configured))
                .sorted(java.util.Comparator.comparing(WorkflowDefinition::name)
                        .thenComparing(WorkflowDefinition::version))
                .toList();
    }

    @Override
    public StartWorkflowResult start(StartWorkflowCommand command) {
        Objects.requireNonNull(command, TEXT_COMMAND);
        IdempotencyFingerprint fingerprint = idempotencyFingerprint(command);
        return withIdempotencyLock(TEXT_START, fingerprint, () -> startLocked(command, fingerprint));
    }

    private StartWorkflowResult startLocked(StartWorkflowCommand command, IdempotencyFingerprint fingerprint) {
        StartWorkflowResult priorResult = findStartResult(TEXT_START, fingerprint);
        if (priorResult != null) {
            return priorResult.asIdempotentRepeat();
        }

        WorkflowInstanceId instanceId = WorkflowInstanceId.random();
        Map<String, Object> safeVariables = command.variables();
        WorkflowDefinition definition = requireDefinition(command);
        String initialState = definition.startNode();
        WorkflowStatus initialStatus = isTerminal(definition, initialState)
                ? WorkflowStatus.COMPLETED
                : WorkflowStatus.RUNNING;
        Instant now = clock.instant();
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                instanceId,
                command.workflowKey(),
                definition.version(),
                definition.revision(),
                command.businessKey(),
                command.metadata().correlationId(),
                initialState,
                initialStatus,
                safeVariables,
                0,
                now,
                now);

        if (shouldAutoRouteStart(definition, snapshot)) {
            snapshot = advanceRoutingNodes(definition, snapshot);
        }
        instances.put(instanceId, snapshot);
        instancesByBusinessKey.put(instanceKey(command.workflowKey(), command.businessKey()), instanceId);
        scheduleTimeout(definition, snapshot, now);
        WorkflowEvent startedEvent = workflowStartedEvent(command, snapshot, now);
        persistStateAndEvent(snapshot, startedEvent);
        eventPublisher.publish(startedEvent);
        StartWorkflowResult result = new StartWorkflowResult(
                command.metadata().commandId(),
                instanceId,
                command.workflowKey(),
                definition.version(),
                command.businessKey(),
                command.metadata().correlationId(),
                now,
                snapshot,
                List.of(startedEvent.metadata().eventId().toString()),
                false);
        remember(TEXT_START, fingerprint, result);
        return result;
    }

    @Override
    public WorkflowCommandResult signal(SignalWorkflowCommand command) {
        Objects.requireNonNull(command, TEXT_COMMAND);
        IdempotencyFingerprint fingerprint = idempotencyFingerprint(command);
        return withIdempotencyLock(TEXT_SIGNAL, fingerprint,
                () -> withInstanceLock(command.instanceId(), () -> signalLocked(command, fingerprint)));
    }

    private WorkflowCommandResult signalLocked(SignalWorkflowCommand command, IdempotencyFingerprint fingerprint) {
        WorkflowCommandResult priorResult = findCommandResult(TEXT_SIGNAL, fingerprint);
        if (priorResult != null) {
            return priorResult.asIdempotentRepeat();
        }
        WorkflowSnapshot snapshot = requireInstance(command.instanceId());
        snapshot = recordJoinCompletion(snapshot, command.signal());
        snapshot = advanceCurrentStep(snapshot, command.signal());
        snapshot = advanceRoutingNodes(snapshot);
        WorkflowCommandResult result = commandResult(command.metadata(), command.instanceId(), WorkflowCommandStatus.ACCEPTED, snapshot);
        remember(TEXT_SIGNAL, fingerprint, result);
        return result;
    }

    private WorkflowSnapshot signal(WorkflowInstanceId instanceId, WorkflowEvent event) {
        return withInstanceLock(instanceId, () -> signalLocked(instanceId, event));
    }

    private WorkflowSnapshot signalLocked(WorkflowInstanceId instanceId, WorkflowEvent event) {
        WorkflowSnapshot snapshot = requireInstance(instanceId);
        WorkflowSignal signal = signalFrom(event, snapshot.businessKey());
        snapshot = recordJoinCompletion(snapshot, signal);
        snapshot = advanceCurrentStep(snapshot, signal, eventVariables(event, snapshot.businessKey()), event);
        snapshot = advanceRoutingNodes(snapshot);
        return snapshot;
    }

    @Override
    public WorkflowCommandResult retryFailedStep(RetryFailedStepCommand command) {
        Objects.requireNonNull(command, TEXT_COMMAND);
        IdempotencyFingerprint fingerprint = idempotencyFingerprint(command);
        return withIdempotencyLock(TEXT_RETRY_FAILED_STEP, fingerprint,
                () -> withInstanceLock(command.instanceId(), () -> retryFailedStepLocked(command, fingerprint)));
    }

    private WorkflowCommandResult retryFailedStepLocked(
            RetryFailedStepCommand command,
            IdempotencyFingerprint fingerprint
    ) {
        WorkflowCommandResult priorResult = findCommandResult(TEXT_RETRY_FAILED_STEP, fingerprint);
        if (priorResult != null) {
            return priorResult.asIdempotentRepeat();
        }
        WorkflowSnapshot snapshot = requireInstance(command.instanceId());
        snapshot = retryFailedStep(snapshot, command.stepId());
        WorkflowCommandResult result = commandResult(command.metadata(), command.instanceId(), WorkflowCommandStatus.ACCEPTED, snapshot);
        remember(TEXT_RETRY_FAILED_STEP, fingerprint, result);
        return result;
    }

    @Override
    public WorkflowCommandResult cancel(CancelWorkflowCommand command) {
        Objects.requireNonNull(command, TEXT_COMMAND);
        IdempotencyFingerprint fingerprint = idempotencyFingerprint(command);
        return withIdempotencyLock(TEXT_CANCEL, fingerprint,
                () -> withInstanceLock(command.instanceId(), () -> cancelLocked(command, fingerprint)));
    }

    private WorkflowCommandResult cancelLocked(CancelWorkflowCommand command, IdempotencyFingerprint fingerprint) {
        WorkflowCommandResult priorResult = findCommandResult(TEXT_CANCEL, fingerprint);
        if (priorResult != null) {
            return priorResult.asIdempotentRepeat();
        }
        WorkflowSnapshot snapshot = requireInstance(command.instanceId());
        WorkflowCommandStatus status = WorkflowCommandStatus.ACCEPTED;
        if (snapshot.status() == WorkflowStatus.COMPLETED || snapshot.status() == WorkflowStatus.CANCELED) {
            status = WorkflowCommandStatus.NO_OP;
        } else {
            snapshot = updateStatus(snapshot, "canceled", WorkflowStatus.CANCELED);
            cancelPendingTimers(snapshot.instanceId());
            eventPublisher.publish(simpleLifecycleEvent("workflow.canceled", snapshot, Map.of()));
        }
        WorkflowCommandResult result = commandResult(command.metadata(), command.instanceId(), status, snapshot);
        remember(TEXT_CANCEL, fingerprint, result);
        return result;
    }

    @Override
    public WorkflowCommandResult resume(ResumeWorkflowCommand command) {
        Objects.requireNonNull(command, TEXT_COMMAND);
        IdempotencyFingerprint fingerprint = idempotencyFingerprint(command);
        return withIdempotencyLock(TEXT_RESUME, fingerprint,
                () -> withInstanceLock(command.instanceId(), () -> resumeLocked(command, fingerprint)));
    }

    private WorkflowCommandResult resumeLocked(ResumeWorkflowCommand command, IdempotencyFingerprint fingerprint) {
        WorkflowCommandResult priorResult = findCommandResult(TEXT_RESUME, fingerprint);
        if (priorResult != null) {
            return priorResult.asIdempotentRepeat();
        }
        WorkflowSnapshot snapshot = requireInstance(command.instanceId());
        WorkflowCommandStatus status;
        if (snapshot.status() == WorkflowStatus.COMPLETED || snapshot.status() == WorkflowStatus.CANCELED) {
            throw new WorkflowInvalidStateException("Terminal workflow instance cannot be resumed: " + snapshot.instanceId());
        } else if (snapshot.status() == WorkflowStatus.RUNNING) {
            status = WorkflowCommandStatus.NO_OP;
        } else {
            snapshot = updateStatus(snapshot, snapshot.state(), WorkflowStatus.RUNNING, snapshot.variables());
            snapshot = advanceCurrentStep(snapshot, null);
            status = WorkflowCommandStatus.ACCEPTED;
        }
        WorkflowCommandResult result = commandResult(command.metadata(), command.instanceId(), status, snapshot);
        remember(TEXT_RESUME, fingerprint, result);
        return result;
    }

    @Override
    public WorkflowSnapshot snapshot(WorkflowInstanceId instanceId) {
        return requireInstance(instanceId);
    }

    @Override
    public WorkflowSnapshot snapshot(String workflowKey, String businessKey) {
        WorkflowInstanceId instanceId = instancesByBusinessKey.get(instanceKey(workflowKey, businessKey));
        if (instanceId == null) {
            throw new WorkflowInstanceNotFoundException("No workflow instance for " + workflowKey + " and " + businessKey);
        }
        return snapshot(instanceId);
    }

    public List<WorkflowTimer> pendingTimers() {
        return timers.stream()
                .filter(timer -> timer.status() == WorkflowTimerStatus.PENDING)
                .toList();
    }

    public List<WorkflowTimer> fireDueTimers(Instant now) {
        Objects.requireNonNull(now, "now");
        ArrayList<WorkflowTimer> fired = new ArrayList<>();
        for (WorkflowTimer timer : pendingTimers()) {
            if (!timer.dueAt().isAfter(now)) {
                WorkflowTimer firedTimer = withInstanceLock(timer.workflowInstanceId(), () -> fireTimer(timer));
                if (firedTimer != null) {
                    fired.add(firedTimer);
                }
            }
        }
        return fired;
    }

    /**
     * Seeds a transient engine used by {@link WorkflowStateMachine}. This is deliberately
     * package-private: repository adapters must use the state-machine boundary rather than
     * treating an in-memory engine as durable state.
     */
    void restore(WorkflowSnapshot snapshot, List<WorkflowTimer> restoredTimers) {
        Objects.requireNonNull(snapshot, "snapshot");
        instances.put(snapshot.instanceId(), snapshot);
        instancesByBusinessKey.put(instanceKey(snapshot.workflowKey(), snapshot.businessKey()), snapshot.instanceId());
        if (restoredTimers != null) {
            restoredTimers.stream()
                    .filter(timer -> timer.workflowInstanceId().equals(snapshot.instanceId()))
                    .forEach(timers::addIfAbsent);
        }
    }

    List<WorkflowTimer> allTimers() {
        return List.copyOf(timers);
    }

    @SuppressWarnings("java:S3776") // Timer guards form one atomic state transition.
    private WorkflowTimer fireTimer(WorkflowTimer timer) {
        WorkflowSnapshot snapshot = instances.get(timer.workflowInstanceId());
        if (snapshot != null
                && (snapshot.status() == WorkflowStatus.RUNNING || snapshot.status() == WorkflowStatus.WAITING)
                && snapshot.state().equals(timer.stepName())
                && timer.status() == WorkflowTimerStatus.PENDING) {
                    WorkflowTimer firedTimer = timer.fired();
                    timers.remove(timer);
                    timers.add(firedTimer);
                    persistTimer(firedTimer);
                    WorkflowSnapshot updated = updateStatus(
                            snapshot,
                            timer.targetNode(),
                            WorkflowStatus.RUNNING,
                            snapshot.variables());
                    WorkflowDefinition definition = findDefinition(updated);
                    if (timer.emittedEvent() != null) {
                        eventPublisher.publish(simpleLifecycleEvent(timer.emittedEvent().value(), updated, Map.of("step", timer.stepName())));
                    }
                    eventPublisher.publish(simpleLifecycleEvent("timer.fired", updated, Map.of("step", timer.stepName())));
                    if (definition != null) {
                        WorkflowNode target = definition.nodes().get(updated.state());
                        if (timer.targetNode().equals(timer.stepName())
                                && target != null && target.type() == WorkflowNodeType.STEP) {
                            updated = advanceCurrentStep(updated, null);
                        } else {
                            updated = advanceRoutingNodes(definition, updated);
                        }
                        if (updated.status() == WorkflowStatus.COMPLETED) {
                            eventPublisher.publish(simpleLifecycleEvent(TEXT_WORKFLOW_COMPLETED, updated, Map.of()));
                        }
                    }
                    return firedTimer;
        }
        return null;
    }

    private WorkflowSnapshot requireInstance(WorkflowInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        WorkflowSnapshot snapshot = instances.get(instanceId);
        if (snapshot == null) {
            throw new WorkflowInstanceNotFoundException(instanceId);
        }
        return snapshot;
    }

    private WorkflowDefinition requireDefinition(StartWorkflowCommand command) {
        if (command.workflowVersion() != null && !command.workflowVersion().isBlank()) {
            return definitions.require(command.workflowKey(), command.workflowVersion());
        }
        return definitions.latest(command.workflowKey())
                .orElseThrow(() -> new WorkflowDefinitionNotFoundException(command.workflowKey(), null));
    }

    private WorkflowDefinition findDefinition(WorkflowSnapshot snapshot) {
        return definitions.find(snapshot.workflowKey(), snapshot.workflowVersion()).orElse(null);
    }

    private static boolean shouldAutoRouteStart(WorkflowDefinition definition, WorkflowSnapshot snapshot) {
        if (definition == null || (snapshot.status() != WorkflowStatus.RUNNING
                && snapshot.status() != WorkflowStatus.WAITING)) {
            return false;
        }
        WorkflowNode startNode = definition.nodes().get(snapshot.state());
        if (startNode == null) {
            return false;
        }
        if (startNode.type() == WorkflowNodeType.FORK) {
            return true;
        }
        return startNode.type() == WorkflowNodeType.GATEWAY
                && startNode.transitions().stream().anyMatch(transition -> TEXT_START.equals(transition.name()));
    }

    private WorkflowSnapshot advanceCurrentStep(WorkflowSnapshot snapshot, WorkflowSignal signal) {
        return advanceCurrentStep(snapshot, signal, Map.of());
    }

    private WorkflowSnapshot advanceCurrentStep(
            WorkflowSnapshot snapshot,
            WorkflowSignal signal,
            Map<String, Object> eventVariables
    ) {
        return advanceCurrentStep(snapshot, signal, eventVariables, null);
    }

    private WorkflowSnapshot advanceCurrentStep(
            WorkflowSnapshot snapshot,
            WorkflowSignal signal,
            Map<String, Object> eventVariables,
            WorkflowEvent event
    ) {
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null || snapshot.status() != WorkflowStatus.RUNNING) {
            return snapshot;
        }
        WorkflowNode node = definition.nodes().get(snapshot.state());
        if (node == null) {
            return snapshot;
        }
        if (node.type() == WorkflowNodeType.WAIT) {
            return advanceWaitNode(definition, snapshot, node, signal);
        }
        if (node.type() != WorkflowNodeType.STEP) {
            return snapshot;
        }
        eventPublisher.publish(simpleLifecycleEvent("step.entered", snapshot,
                Map.of("step", node.name(), TEXT_ACTION, node.action() == null ? "" : node.action())));
        StepHandler handler = stepHandlers.get(node.action());
        if (handler == null) {
            if (signal != null && isStepSuccessEvent(node, signal.eventType())) {
                WorkflowSnapshot routed = routeStepSuccess(definition, snapshot, node, eventVariables);
                invokeCurrentStepListener(routed, signal, event);
                return routed;
            }
            if (signal != null && node.listenerClassName() != null && node.listenerMethodName() != null) {
                invokeListener(snapshot, definition, node, signal, event);
            }
            return snapshot;
        }

        try {
            StepResult stepResult = handler.handle(new StepContext(
                    snapshot.instanceId(),
                    snapshot.workflowKey(),
                    snapshot.businessKey(),
                    node.name(),
                    node.action(),
                    snapshot.variables(),
                    signal));
            if (!stepResult.successful()) {
                return handleStepFailure(definition, snapshot, node,
                        merge(snapshot.variables(), stepResult.variables()), null);
            }
            return routeStepSuccess(definition, snapshot, node, stepResult.variables());
        } catch (Exception exception) {
            return handleStepFailure(definition, snapshot, node, snapshot.variables(), exception);
        }
    }

    private void invokeCurrentStepListener(WorkflowSnapshot snapshot, WorkflowSignal signal, WorkflowEvent event) {
        if (snapshot.status() != WorkflowStatus.RUNNING) {
            return;
        }
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null) {
            return;
        }
        WorkflowNode node = definition.nodes().get(snapshot.state());
        if (node != null
                && node.type() == WorkflowNodeType.STEP
                && node.listenerClassName() != null
                && node.listenerMethodName() != null) {
            invokeListener(snapshot, definition, node, signal, event);
        }
    }

    @SuppressWarnings("java:S3776") // Validation and exception translation share one invocation boundary.
    private void invokeListener(
            WorkflowSnapshot snapshot,
            WorkflowDefinition definition,
            WorkflowNode node,
            WorkflowSignal signal,
            WorkflowEvent event
    ) {
        Object listener = listenerInstances.get(node.listenerClassName());
        if (listener == null) {
            throw new WorkflowInfrastructureException("No listener registered for " + node.listenerClassName(), null);
        }
        WorkflowEvent listenerEvent = event == null && signal != null ? eventFromSignal(signal, snapshot) : event;
        WorkflowExecutionContext executionContext = new WorkflowExecutionContext(
                snapshot.instanceId(),
                snapshot.workflowKey(),
                definition.version(),
                node.name(),
                listenerEvent,
                signal == null ? null : signal.correlationId(),
                snapshot.businessKey(),
                signal == null ? null : signal.causationId(),
                null);
        try {
        try (WorkflowExecutionContext.Scope ignored = WorkflowExecutionContext.bind(executionContext)) {
                if (node.listenerInvocation() != null) {
                    Object[] arguments = listenerArguments(node.listenerInvocation(), listenerEvent, executionContext);
                    Method method = listenerMethod(listener, node.listenerInvocation().methodName(), arguments);
                    method.invoke(listener, arguments);
                } else {
                    Method method = listenerMethod(listener, node.listenerMethodName());
                    if (method.getParameterCount() == 0) {
                        method.invoke(listener);
                    } else if (method.getParameterTypes()[0].equals(WorkflowEvent.class)) {
                        method.invoke(listener, listenerEvent);
                    } else {
                        method.invoke(listener, signal == null ? snapshot.businessKey() : signal.businessKey());
                    }
                }
            }
        } catch (NoSuchMethodException exception) {
            throw new WorkflowInfrastructureException(
                    "Listener method must accept no arguments, WorkflowEvent, or workflow id as String: "
                            + node.listenerClassName()
                            + "#"
                            + node.listenerMethodName(),
                    exception);
        } catch (ReflectiveOperationException exception) {
            throw new WorkflowInfrastructureException(
                    "Failed to invoke listener "
                            + node.listenerClassName()
                            + "#"
                            + node.listenerMethodName(),
                    exception);
        }
    }

    private static Method listenerMethod(Object listener, String methodName) throws NoSuchMethodException {
        try {
            return listener.getClass().getMethod(methodName);
        } catch (NoSuchMethodException ignored) {
            try {
                return listener.getClass().getMethod(methodName, WorkflowEvent.class);
            } catch (NoSuchMethodException ignoredAgain) {
                return listener.getClass().getMethod(methodName, String.class);
            }
        }
    }

    private static Object[] listenerArguments(
            ListenerInvocation invocation,
            WorkflowEvent event,
            WorkflowExecutionContext context
    ) {
        return invocation.arguments().stream().map(argument -> {
            if (argument instanceof ListenerArgument.CurrentEvent) {
                return event;
            }
            if (argument instanceof ListenerArgument.CurrentContext) {
                return context;
            }
            return ((ListenerArgument.Literal) argument).value();
        }).toArray();
    }

    @SuppressWarnings("java:S3776") // Reflection matching validates every candidate and argument.
    private static Method listenerMethod(Object listener, String methodName, Object[] arguments) throws NoSuchMethodException {
        Method match = null;
        for (Method candidate : listener.getClass().getMethods()) {
            if (!candidate.getName().equals(methodName) || candidate.getParameterCount() != arguments.length
                    || java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                continue;
            }
            boolean compatible = true;
            for (int index = 0; index < arguments.length; index++) {
                if (arguments[index] != null && !candidate.getParameterTypes()[index].isInstance(arguments[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                if (match != null) {
                    throw new NoSuchMethodException("Ambiguous listener method: " + methodName);
                }
                match = candidate;
            }
        }
        if (match == null) {
            throw new NoSuchMethodException(methodName);
        }
        return match;
    }

    private WorkflowSnapshot routeStepSuccess(
            WorkflowDefinition definition,
            WorkflowSnapshot snapshot,
            WorkflowNode node,
            Map<String, Object> variables
    ) {
        Map<String, Object> mergedVariables = new LinkedHashMap<>(merge(snapshot.variables(), variables));
        mergedVariables.remove(retryKey(node.name()));
        WorkflowTransition transition = selectStepTransition(node, TEXT_SUCCESS);
        if (transition == null) {
            return updateStatus(snapshot, snapshot.state(), WorkflowStatus.COMPLETED, mergedVariables);
        }
        WorkflowSnapshot updated = updateStatus(
                snapshot,
                transition.targetNode(),
                WorkflowStatus.RUNNING,
                mergedVariables);
        scheduleTimeout(definition, updated, clock.instant());
        eventPublisher.publish(simpleLifecycleEvent("step.completed", updated, Map.of("step", node.name(), TEXT_ACTION, node.action())));
        eventPublisher.publish(simpleLifecycleEvent(TEXT_TRANSITION_TAKEN, updated, Map.of("from", node.name(), "to", transition.targetNode())));
        updated = advanceRoutingNodes(definition, updated);
        if (updated.status() == WorkflowStatus.COMPLETED) {
            eventPublisher.publish(simpleLifecycleEvent(TEXT_WORKFLOW_COMPLETED, updated, Map.of()));
        }
        return updated;
    }

    private WorkflowSnapshot advanceWaitNode(
            WorkflowDefinition definition,
            WorkflowSnapshot snapshot,
            WorkflowNode node,
            WorkflowSignal signal
    ) {
        if (signal == null || !node.waitDefinition().eventName().value().equals(signal.eventType())) {
            return snapshot;
        }
        WorkflowSnapshot updated = updateStatus(snapshot, node.waitDefinition().targetNode(), WorkflowStatus.RUNNING,
                merge(snapshot.variables(), signalData(signal)));
        scheduleTimeout(definition, updated, clock.instant());
        eventPublisher.publish(simpleLifecycleEvent(
                TEXT_TRANSITION_TAKEN,
                updated,
                Map.of("from", node.name(), "to", node.waitDefinition().targetNode())));
        updated = advanceRoutingNodes(definition, updated);
        if (updated.status() == WorkflowStatus.COMPLETED) {
            eventPublisher.publish(simpleLifecycleEvent(TEXT_WORKFLOW_COMPLETED, updated, Map.of()));
        }
        return updated;
    }

    private WorkflowSnapshot routeStepFailure(
            WorkflowDefinition definition,
            WorkflowSnapshot snapshot,
            WorkflowNode node,
            Map<String, Object> variables
    ) {
        WorkflowTransition failure = selectStepTransition(node, "failure");
        if (failure == null) {
            return updateStatus(snapshot, snapshot.state(), WorkflowStatus.FAILED, variables);
        }
        WorkflowSnapshot updated = updateStatus(snapshot, failure.targetNode(), WorkflowStatus.RUNNING, variables);
        scheduleTimeout(definition, updated, clock.instant());
        eventPublisher.publish(simpleLifecycleEvent(
                TEXT_TRANSITION_TAKEN,
                updated,
                Map.of("from", node.name(), "to", failure.targetNode())));
        updated = advanceRoutingNodes(definition, updated);
        if (updated.status() == WorkflowStatus.COMPLETED) {
            eventPublisher.publish(simpleLifecycleEvent(TEXT_WORKFLOW_COMPLETED, updated, Map.of()));
        }
        return updated;
    }

    private WorkflowSnapshot handleStepFailure(
            WorkflowDefinition definition,
            WorkflowSnapshot snapshot,
            WorkflowNode node,
            Map<String, Object> variables,
            Exception exception
    ) {
        LinkedHashMap<String, Object> failedVariables = new LinkedHashMap<>(variables);
        String retryKey = retryKey(node.name());
        int attempts = ((Number) failedVariables.getOrDefault(retryKey, 0)).intValue() + 1;
        failedVariables.put(retryKey, attempts);
        Map<String, String> failureHeaders = new LinkedHashMap<>();
        failureHeaders.put("step", node.name());
        failureHeaders.put(TEXT_ACTION, node.action());
        failureHeaders.put(TEXT_ATTEMPT, Integer.toString(attempts));
        if (exception != null) {
            failureHeaders.put("error", exception.getClass().getName());
        }
        eventPublisher.publish(simpleLifecycleEvent("step.failed", snapshot, failureHeaders));

        RetryPolicy retry = node.retryPolicy();
        if (retry != null && attempts < retry.maxAttempts()) {
            WorkflowSnapshot waiting = updateStatus(snapshot, snapshot.state(), WorkflowStatus.WAITING, failedVariables);
            scheduleRetry(waiting, node, retry.backoff(), attempts + 1);
            return waiting;
        }
        WorkflowSnapshot failed = routeStepFailure(definition, snapshot, node, failedVariables);
        if (failed.status() == WorkflowStatus.FAILED) {
            eventPublisher.publish(simpleLifecycleEvent("workflow.failed", failed, failureHeaders));
        }
        return failed;
    }

    private static WorkflowTransition selectStepTransition(WorkflowNode node, String name) {
        WorkflowTransition fallback = null;
        for (WorkflowTransition transition : node.transitions()) {
            if (name.equals(transition.name())) {
                return transition;
            }
            if (fallback == null && transition.name() == null) {
                fallback = transition;
            }
        }
        return TEXT_SUCCESS.equals(name) ? fallback : null;
    }

    private WorkflowSnapshot retryFailedStep(WorkflowSnapshot snapshot, String stepId) {
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null) {
            return snapshot;
        }
        if (snapshot.status() != WorkflowStatus.FAILED) {
            throw new WorkflowInvalidStateException("Workflow instance is not failed: " + snapshot.instanceId());
        }
        if (!snapshot.state().equals(stepId)) {
            throw new WorkflowInvalidStateException("Failed workflow is at step "
                    + snapshot.state()
                    + " and cannot retry "
                    + stepId);
        }
        WorkflowNode node = definition.nodes().get(stepId);
        if (node == null || node.type() != WorkflowNodeType.STEP) {
            throw new WorkflowInvalidStateException("Retry target is not a workflow step: " + stepId);
        }
        int attempts = ((Number) snapshot.variables().getOrDefault(retryKey(stepId), 0)).intValue();
        if (node.retryPolicy() != null && attempts >= node.retryPolicy().maxAttempts()) {
            throw new WorkflowInvalidStateException("Retry attempts exhausted for step " + stepId);
        }
        WorkflowSnapshot runningSnapshot = updateStatus(snapshot, snapshot.state(), WorkflowStatus.RUNNING, snapshot.variables());
        Duration backoff = node.retryPolicy() == null ? Duration.ZERO : node.retryPolicy().backoff();
        if (!backoff.isZero()) {
            scheduleRetry(runningSnapshot, node, backoff, attempts + 1);
            return updateStatus(runningSnapshot, runningSnapshot.state(), WorkflowStatus.WAITING, runningSnapshot.variables());
        }
        eventPublisher.publish(simpleLifecycleEvent("retry.scheduled", runningSnapshot,
                Map.of("step", stepId, TEXT_ATTEMPT, Integer.toString(attempts + 1))));
        return advanceCurrentStep(runningSnapshot, null);
    }

    @SuppressWarnings("java:S3776") // Explicit workflow-node state-machine dispatch loop.
    private WorkflowSnapshot advanceRoutingNodes(WorkflowDefinition definition, WorkflowSnapshot snapshot) {
        WorkflowSnapshot current = snapshot;
        boolean advanced;
        do {
            advanced = false;
            WorkflowNode node = definition.nodes().get(current.state());
            if (node == null) {
                return current;
            }
            if (node.type() == WorkflowNodeType.END) {
                return updateStatus(current, current.state(), WorkflowStatus.COMPLETED, current.variables());
            }
            if (node.type() == WorkflowNodeType.GATEWAY) {
                WorkflowTransition transition = selectGatewayTransition(node, current.variables());
                if (transition == null) {
                    throw new WorkflowInvalidStateException("Gateway has no matching route: " + node.name());
                }
                WorkflowSnapshot updated = updateStatus(
                        current,
                        transition.targetNode(),
                        WorkflowStatus.RUNNING,
                        current.variables());
                scheduleTimeout(definition, updated, clock.instant());
                eventPublisher.publish(simpleLifecycleEvent(
                        TEXT_TRANSITION_TAKEN,
                        updated,
                        Map.of("from", node.name(), "to", transition.targetNode())));
                current = updated;
                advanced = true;
            }
            if (node.type() == WorkflowNodeType.LOOP) {
                LoopRoute loopRoute = selectLoopRoute(node, current.variables());
                WorkflowSnapshot updated = updateStatus(
                        current,
                        loopRoute.targetNode(),
                        WorkflowStatus.RUNNING,
                        loopRoute.variables());
                scheduleTimeout(definition, updated, clock.instant());
                eventPublisher.publish(simpleLifecycleEvent(
                        TEXT_TRANSITION_TAKEN,
                        updated,
                        Map.of("from", node.name(), "to", loopRoute.targetNode())));
                current = updated;
                advanced = true;
            }
            if (node.type() == WorkflowNodeType.FORK) {
                String forkExecutionId = java.util.UUID.randomUUID().toString();
                LinkedHashMap<String, Object> forkVariables = new LinkedHashMap<>(current.variables());
                forkVariables.put(forkExecutionKey(node.name()), forkExecutionId);
                WorkflowSnapshot updated = updateStatus(
                        current,
                        node.fork().joinNode(),
                        WorkflowStatus.RUNNING,
                        forkVariables);
                for (Map.Entry<String, String> branch : node.fork().branches().entrySet()) {
                    eventPublisher.publish(simpleLifecycleEvent(
                            "branch.started",
                            updated,
                            Map.of(
                                    "fork", node.name(),
                                    "forkExecutionId", forkExecutionId,
                                    "branch", branch.getKey(),
                                    "target", branch.getValue(),
                                    "join", node.fork().joinNode())));
                }
                eventPublisher.publish(simpleLifecycleEvent(
                        TEXT_TRANSITION_TAKEN,
                        updated,
                        Map.of("from", node.name(), "to", node.fork().joinNode())));
                current = updated;
                advanced = true;
            }
            if (node.type() == WorkflowNodeType.SUB_WORKFLOW) {
                SubWorkflowRoute route = callSubWorkflow(node, current);
                WorkflowSnapshot updated = updateStatus(
                        current,
                        route.targetNode(),
                        WorkflowStatus.RUNNING,
                        route.variables());
                scheduleTimeout(definition, updated, clock.instant());
                eventPublisher.publish(simpleLifecycleEvent(
                        route.eventName().value(),
                        updated,
                        Map.of("subWorkflow", node.name(), "targetWorkflow", node.subWorkflow().workflowName())));
                eventPublisher.publish(simpleLifecycleEvent(
                        TEXT_TRANSITION_TAKEN,
                        updated,
                        Map.of("from", node.name(), "to", route.targetNode())));
                current = updated;
                advanced = true;
            }
        } while (advanced);
        return current;
    }

    private WorkflowSnapshot recordJoinCompletion(WorkflowSnapshot snapshot, WorkflowSignal signal) {
        if (signal == null || signal.metadata() == null || snapshot.status() != WorkflowStatus.RUNNING) {
            return snapshot;
        }
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null) {
            return snapshot;
        }
        WorkflowNode node = definition.nodes().get(snapshot.state());
        if (node == null || node.type() != WorkflowNodeType.JOIN) {
            return snapshot;
        }
        String branch = signal.metadata().get("branch");
        if (branch == null || branch.isBlank() || !node.join().requiredBranches().contains(branch)) {
            return snapshot;
        }

        WorkflowNode forkNode = definition.nodes().values().stream()
                .filter(candidate -> candidate.type() == WorkflowNodeType.FORK
                        && node.name().equals(candidate.fork().joinNode()))
                .findFirst()
                .orElse(null);
        if (forkNode == null) {
            return snapshot;
        }
        String forkExecutionId = Objects.toString(snapshot.variables().get(forkExecutionKey(forkNode.name())), "");
        String signaledExecutionId = signal.metadata().get("forkExecutionId");
        if (forkExecutionId.isBlank() || signaledExecutionId == null || !forkExecutionId.equals(signaledExecutionId)) {
            return snapshot;
        }
        String completionKey = "__jworkflow.join." + node.name() + "." + forkExecutionId + ".completed";
        LinkedHashMap<String, Object> variables = new LinkedHashMap<>(snapshot.variables());
        String currentValue = Objects.toString(variables.getOrDefault(completionKey, ""), "");
        java.util.LinkedHashSet<String> completed = new java.util.LinkedHashSet<>();
        if (!currentValue.isBlank()) {
            completed.addAll(List.of(currentValue.split(",")));
        }
        completed.add(branch);
        variables.put(completionKey, String.join(",", completed));
        WorkflowSnapshot updated = updateStatus(snapshot, snapshot.state(), WorkflowStatus.RUNNING, variables);

        if (completed.containsAll(node.join().requiredBranches())) {
            WorkflowSnapshot advanced = updateStatus(updated, node.join().nextNode(), WorkflowStatus.RUNNING, variables);
            if (node.join().emittedEvent() != null) {
                eventPublisher.publish(simpleLifecycleEvent(node.join().emittedEvent().value(), advanced, Map.of("join", node.name())));
            }
            eventPublisher.publish(simpleLifecycleEvent(
                    TEXT_TRANSITION_TAKEN,
                    advanced,
                    Map.of("from", node.name(), "to", node.join().nextNode())));
            return advanced;
        }
        return updated;
    }

    private WorkflowSnapshot advanceRoutingNodes(WorkflowSnapshot snapshot) {
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null || snapshot.status() != WorkflowStatus.RUNNING) {
            return snapshot;
        }
        return advanceRoutingNodes(definition, snapshot);
    }

    private WorkflowTransition selectGatewayTransition(WorkflowNode node, Map<String, Object> variables) {
        WorkflowTransition otherwise = null;
        for (WorkflowTransition transition : node.transitions()) {
            if (transition.condition() == null) {
                otherwise = transition;
            } else if (branchConditionEvaluator.evaluate(transition.condition(), variables)) {
                return transition;
            }
        }
        return otherwise;
    }

    private SubWorkflowRoute callSubWorkflow(WorkflowNode node, WorkflowSnapshot callerSnapshot) {
        SubWorkflowDefinition subWorkflow = node.subWorkflow();
        Map<String, Object> inputs = subWorkflowInputs(subWorkflow, callerSnapshot.variables());
        StartWorkflowResult result = start(new StartWorkflowCommand(
                subWorkflow.workflowName(),
                subWorkflow.workflowVersion(),
                callerSnapshot.businessKey(),
                inputs,
                WorkflowCommandMetadata.defaults(
                        subWorkflow.workflowName(),
                        subWorkflow.workflowVersion(),
                        null,
                        callerSnapshot.businessKey())));
        WorkflowSnapshot childSnapshot = driveSubWorkflow(result.snapshot());
        LinkedHashMap<String, Object> variables = new LinkedHashMap<>(callerSnapshot.variables());
        variables.put("__jworkflow.subWorkflow." + node.name() + ".instanceId", result.workflowInstanceId().toString());
        variables.put("__jworkflow.subWorkflow." + node.name() + ".status", childSnapshot.status().name());
        if (childSnapshot.status() == WorkflowStatus.FAILED) {
            return new SubWorkflowRoute(
                    subWorkflow.failureTargetNode(),
                    subWorkflow.failureEvent(),
                    variables);
        }
        return new SubWorkflowRoute(
                subWorkflow.successTargetNode(),
                subWorkflow.successEvent(),
                variables);
    }

    private WorkflowSnapshot driveSubWorkflow(WorkflowSnapshot snapshot) {
        WorkflowSnapshot current = snapshot;
        for (int guard = 0; guard < 100 && current.status() == WorkflowStatus.RUNNING; guard++) {
            WorkflowSnapshot before = current;
            current = advanceCurrentStep(current, null);
            current = advanceRoutingNodes(current);
            if (before.state().equals(current.state())
                    && before.status() == current.status()
                    && before.updatedAt().equals(current.updatedAt())) {
                return current;
            }
        }
        return current;
    }

    private static Map<String, Object> subWorkflowInputs(SubWorkflowDefinition subWorkflow, Map<String, Object> callerVariables) {
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : subWorkflow.inputMappings().entrySet()) {
            if (callerVariables.containsKey(mapping.getKey())) {
                inputs.put(mapping.getValue(), callerVariables.get(mapping.getKey()));
            }
        }
        return inputs;
    }

    private LoopRoute selectLoopRoute(WorkflowNode node, Map<String, Object> variables) {
        LoopDefinition loop = node.loop();
        String iterationKey = "__jworkflow.loop." + node.name() + ".iterations";
        int iterations = ((Number) variables.getOrDefault(iterationKey, 0)).intValue();
        boolean shouldLoop = branchConditionEvaluator.evaluate(loop.whileCondition(), variables);
        LinkedHashMap<String, Object> nextVariables = new LinkedHashMap<>(variables);
        if (shouldLoop && iterations < loop.maxIterations()) {
            nextVariables.put(iterationKey, iterations + 1);
            return new LoopRoute(loop.stepNode(), nextVariables);
        }
        return new LoopRoute(loop.nextNode(), nextVariables);
    }

    private static boolean isTerminal(WorkflowDefinition definition, String nodeName) {
        WorkflowNode node = definition.nodes().get(nodeName);
        return node != null && node.type() == WorkflowNodeType.END;
    }

    private void scheduleTimeout(WorkflowDefinition definition, WorkflowSnapshot snapshot, Instant now) {
        if (definition == null || snapshot.status() != WorkflowStatus.RUNNING) {
            return;
        }
        ensureEventLoopStarted();
        WorkflowNode node = definition.nodes().get(snapshot.state());
        if (node == null || node.timeout() == null || node.timeout().targetNode() == null || node.timeout().targetNode().isBlank()) {
            return;
        }
        boolean alreadyPending = timers.stream().anyMatch(timer ->
                timer.status() == WorkflowTimerStatus.PENDING
                        && timer.workflowInstanceId().equals(snapshot.instanceId())
                        && timer.stepName().equals(snapshot.state()));
        if (alreadyPending) {
            return;
        }
        WorkflowTimer timer = new WorkflowTimer(
                null,
                snapshot.instanceId(),
                snapshot.state(),
                now.plus(node.timeout().duration()),
                node.timeout().targetNode(),
                node.timeout().emittedEvent(),
                "PENDING");
        timers.add(timer);
        persistTimer(timer);
    }

    private static String forkExecutionKey(String forkNode) {
        return "__jworkflow.fork." + forkNode + ".execution";
    }

    private void scheduleRetry(WorkflowSnapshot snapshot, WorkflowNode node, Duration backoff, int nextAttempt) {
        ensureEventLoopStarted();
        WorkflowTimer timer = new WorkflowTimer(
                null,
                snapshot.instanceId(),
                node.name(),
                clock.instant().plus(backoff),
                node.name(),
                null,
                "PENDING");
        timers.add(timer);
        persistTimer(timer);
        eventPublisher.publish(simpleLifecycleEvent("retry.scheduled", snapshot,
                Map.of("step", node.name(), TEXT_ATTEMPT, Integer.toString(nextAttempt))));
    }

    private void cancelPendingTimers(WorkflowInstanceId instanceId) {
        cancelPendingTimers(instanceId, null);
    }

    private void cancelPendingTimers(WorkflowInstanceId instanceId, String stepName) {
        for (WorkflowTimer timer : List.copyOf(timers)) {
            if (timer.status() == WorkflowTimerStatus.PENDING
                    && timer.workflowInstanceId().equals(instanceId)
                    && (stepName == null || timer.stepName().equals(stepName))) {
                timers.remove(timer);
                WorkflowTimer canceled = timer.canceled();
                timers.add(canceled);
                persistTimer(canceled);
            }
        }
    }

    private static String retryKey(String stepName) {
        return "_jworkflow.retry." + stepName;
    }

    private static WorkflowEvent workflowStartedEvent(StartWorkflowCommand command, WorkflowSnapshot snapshot, Instant now) {
        return new WorkflowEvent(
                new EventMetadata(
                        null,
                        new EventName("workflow.started"),
                        TEXT_JWORKFLOW,
                        command.metadata().correlationId(),
                        command.metadata().causationId(),
                        command.metadata().traceId(),
                        snapshot.instanceId(),
                        snapshot.businessKey(),
                        command.metadata().tenantId(),
                        "1",
                        now,
                        now,
                        Map.of(
                                TEXT_WORKFLOW_KEY, snapshot.workflowKey(),
                                TEXT_WORKFLOW_VERSION, snapshot.workflowVersion(),
                                TEXT_STATE, snapshot.state(),
                                "status", snapshot.status().name())),
                EventMessage.empty());
    }

    private boolean acceptsEvent(WorkflowSnapshot snapshot, WorkflowEvent event) {
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null) {
            return false;
        }
        WorkflowNode node = definition.nodes().get(snapshot.state());
        if (node == null) {
            return false;
        }
        String eventName = event.eventName().value();
        if (node.type() == WorkflowNodeType.WAIT) {
            return node.waitDefinition().eventName().value().equals(eventName);
        }
        if (node.type() != WorkflowNodeType.STEP) {
            return false;
        }
        if (isStepSuccessEvent(node, eventName)) {
            return true;
        }
        if (snapshot.state().equals(definition.startNode())
                && eventName.equals(definition.metadata().get("startEvent"))) {
            return true;
        }
        return definition.nodes().values().stream()
                .flatMap(previous -> previous.transitions().stream())
                .anyMatch(transition -> snapshot.state().equals(transition.targetNode())
                        && transition.emittedEvent() != null
                        && eventName.equals(transition.emittedEvent().value()));
    }

    private String correlationValue(WorkflowSnapshot snapshot, WorkflowEvent event) {
        WorkflowDefinition definition = findDefinition(snapshot);
        if (definition == null) {
            throw new WorkflowDefinitionNotFoundException(snapshot.workflowKey(), snapshot.workflowVersion());
        }
        WorkflowNode node = definition.nodes().get(snapshot.state());
        String correlationKey = node != null && node.waitDefinition() != null
                && node.waitDefinition().correlateBy() != null
                && !node.waitDefinition().correlateBy().isBlank()
                ? node.waitDefinition().correlateBy()
                : definition.metadata().get("correlateBy");
        return correlationValue(correlationKey, event);
    }

    private static String correlationValue(WorkflowDefinition definition, WorkflowEvent event) {
        return correlationValue(definition.metadata().get("correlateBy"), event);
    }

    private static String correlationValue(String correlationKey, WorkflowEvent event) {
        Object value = null;
        if (correlationKey != null && !correlationKey.isBlank()) {
            value = event.metadata().headers().get(correlationKey);
            if (value == null && event.message().payload() instanceof Map<?, ?> payload) {
                value = payload.get(correlationKey);
            }
        }
        if (value == null) {
            value = event.metadata().businessKey();
        }
        if (value == null) {
            value = event.metadata().headers().get(TEXT_BUSINESS_KEY);
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Published workflow event must include correlation key "
                    + (correlationKey == null || correlationKey.isBlank() ? TEXT_BUSINESS_KEY : correlationKey));
        }
        return value.toString();
    }

    private static Map<String, Object> eventVariables(WorkflowEvent event, String businessKey) {
        LinkedHashMap<String, Object> variables = new LinkedHashMap<>();
        variables.putAll(event.metadata().headers());
        Object payload = event.message().payload();
        if (payload instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    variables.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        variables.putIfAbsent(TEXT_BUSINESS_KEY, businessKey);
        return variables;
    }

    private WorkflowSignal signalFrom(WorkflowEvent event, String businessKey) {
        return new WorkflowSignal(
                event.eventName().value(),
                eventCorrelationId(event),
                event.metadata().eventId().toString(),
                businessKey,
                event.metadata().occurredAt() == null ? clock.instant() : event.metadata().occurredAt(),
                event.metadata().headers());
    }

    private static String eventCorrelationId(WorkflowEvent event) {
        String correlationId = event.metadata().correlationId();
        return correlationId == null || correlationId.isBlank()
                ? event.metadata().eventId().toString()
                : correlationId;
    }

    private static boolean isStepSuccessEvent(WorkflowNode node, String eventType) {
        for (WorkflowTransition transition : node.transitions()) {
            if (TEXT_SUCCESS.equals(transition.name())
                    && transition.emittedEvent() != null
                    && transition.emittedEvent().value().equals(eventType)) {
                return true;
            }
        }
        return successEventName(node.action()).equals(eventType);
    }

    private static String successEventName(String action) {
        int separator = action.indexOf('.');
        if (separator < 1 || separator == action.length() - 1) {
            return action;
        }
        String subject = action.substring(0, separator);
        String verb = action.substring(separator + 1);
        return subject + "." + pastTense(verb);
    }

    private static String pastTense(String verb) {
        if (verb.endsWith("e")) {
            return verb + "d";
        }
        return verb + "ed";
    }

    private static String instanceKey(String workflowKey, String businessKey) {
        return workflowKey + "|" + businessKey;
    }

    private static WorkflowEvent enrichFromCurrentContext(WorkflowEvent event) {
        return WorkflowExecutionContext.current()
                .map(context -> {
                    Map<String, String> headers = new LinkedHashMap<>(event.metadata().headers());
                    headers.putIfAbsent("workflowName", context.workflowName());
                    headers.putIfAbsent("workflowStep", context.stepName());
                    headers.putIfAbsent(TEXT_BUSINESS_KEY, context.businessKey());
                    return event.withMetadata(event.metadata().withWorkflowContext(
                            context.correlationId(),
                            context.event() == null ? context.causationId() : context.event().metadata().eventId().toString(),
                            context.traceId(),
                            context.workflowInstanceId(),
                            context.businessKey(),
                            headers));
                })
                .orElse(event);
    }

    private WorkflowEvent eventFromSignal(WorkflowSignal signal, WorkflowSnapshot snapshot) {
        Instant now = signal.occurredAt() == null ? clock.instant() : signal.occurredAt();
        return new WorkflowEvent(
                new EventMetadata(
                        null,
                        new EventName(signal.eventType()),
                        TEXT_JWORKFLOW,
                        signal.correlationId(),
                        signal.causationId(),
                        null,
                        snapshot.instanceId(),
                        signal.businessKey(),
                        null,
                        "1",
                        now,
                        now,
                        signal.metadata()),
                EventMessage.empty());
    }

    private static final class WorkflowThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jworkflow-events");
            thread.setDaemon(true);
            return thread;
        }
    }

    private WorkflowSnapshot updateStatus(WorkflowSnapshot snapshot, String state, WorkflowStatus status) {
        return updateStatus(snapshot, state, status, new LinkedHashMap<>(snapshot.variables()));
    }

    private WorkflowSnapshot updateStatus(
            WorkflowSnapshot snapshot,
            String state,
            WorkflowStatus status,
            Map<String, Object> variables
    ) {
        if (!snapshot.state().equals(state)
                || status == WorkflowStatus.COMPLETED
                || status == WorkflowStatus.CANCELED
                || status == WorkflowStatus.FAILED) {
            cancelPendingTimers(snapshot.instanceId(), snapshot.state());
        }
        WorkflowSnapshot updated = new WorkflowSnapshot(
                snapshot.instanceId(),
                snapshot.workflowKey(),
                snapshot.workflowVersion(),
                snapshot.workflowRevision(),
                snapshot.businessKey(),
                snapshot.correlationId(),
                state,
                status,
                variables,
                snapshot.lockVersion() + 1,
                snapshot.createdAt(),
                clock.instant());
        instances.put(snapshot.instanceId(), updated);
        persistSnapshot(updated);
        return updated;
    }

    private static Map<String, Object> merge(Map<String, Object> original, Map<String, Object> updates) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(original == null ? Map.of() : original);
        if (updates != null) {
            merged.putAll(updates);
        }
        return merged;
    }

    private WorkflowEvent simpleLifecycleEvent(
            String name,
            WorkflowSnapshot snapshot,
            Map<String, String> headers
    ) {
        Instant now = clock.instant();
        LinkedHashMap<String, String> observationHeaders = new LinkedHashMap<>();
        observationHeaders.put(TEXT_WORKFLOW_KEY, snapshot.workflowKey());
        observationHeaders.put(TEXT_WORKFLOW_VERSION, snapshot.workflowVersion());
        observationHeaders.put(TEXT_STATE, snapshot.state());
        observationHeaders.put("status", snapshot.status().name());
        observationHeaders.putAll(headers);
        WorkflowEvent event = SafeEventCapturePolicy.filter(eventCapturePolicy, new WorkflowEvent(
                new EventMetadata(
                        null,
                        new EventName(name),
                        TEXT_JWORKFLOW,
                        null,
                        null,
                        null,
                        snapshot.instanceId(),
                        snapshot.businessKey(),
                        null,
                        "1",
                        now,
                        now,
                        observationHeaders),
                EventMessage.empty()));
        if (persistence != null) {
            persistence.transactions().execute(() -> persistence.events().append(event));
        }
        return event;
    }

    private static Map<String, Object> signalData(WorkflowSignal signal) {
        if (signal == null || signal.message() == null || signal.message().payload() == null) return Map.of();
        Object payload = signal.message().payload();
        if (payload instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        }
        return Map.of("_eventPayload", payload);
    }

    private WorkflowEvent incomingObservation(String name, WorkflowEvent incoming, WorkflowSnapshot snapshot) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("eventName", incoming.eventName().value());
        if (snapshot != null) {
            headers.put(TEXT_WORKFLOW_KEY, snapshot.workflowKey());
            headers.put(TEXT_WORKFLOW_VERSION, snapshot.workflowVersion());
            headers.put(TEXT_STATE, snapshot.state());
        }
        EventMetadata metadata = incoming.metadata();
        return new WorkflowEvent(new EventMetadata(null, new EventName(name), TEXT_JWORKFLOW,
                metadata.correlationId(), metadata.causationId(), metadata.traceId(),
                snapshot == null ? metadata.workflowInstanceId() : snapshot.instanceId(),
                snapshot == null ? metadata.businessKey() : snapshot.businessKey(), metadata.tenantId(), "1",
                clock.instant(), clock.instant(), headers), EventMessage.empty());
    }

    private void persistSnapshot(WorkflowSnapshot snapshot) {
        if (persistence != null) {
            persistence.transactions().execute(() -> persistence.instances().update(snapshot, snapshot.lockVersion() - 1));
        }
    }

    private void persistStateAndEvent(WorkflowSnapshot snapshot, WorkflowEvent event) {
        if (persistence != null) {
            persistence.transactions().execute(() -> {
                persistence.instances().insert(snapshot);
                persistence.events().append(SafeEventCapturePolicy.filter(eventCapturePolicy, event));
            });
        }
    }

    private void persistTimer(WorkflowTimer timer) {
        if (persistence != null) {
            persistence.transactions().execute(() -> persistence.timers().save(timer));
        }
    }

    private static WorkflowCommandResult commandResult(
            WorkflowCommandMetadata metadata,
            WorkflowInstanceId instanceId,
            WorkflowCommandStatus status,
            WorkflowSnapshot snapshot
    ) {
        return new WorkflowCommandResult(
                metadata.commandId(),
                instanceId,
                status,
                snapshot,
                List.of(),
                List.of(),
                false);
    }

    private StartWorkflowResult findStartResult(String commandType, IdempotencyFingerprint fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        IdempotentResult stored = idempotentResults.get(commandType + ":" + fingerprint.idempotencyKey());
        if (stored == null) {
            return null;
        }
        stored.requireSameFingerprint(fingerprint);
        return (StartWorkflowResult) stored.result();
    }

    private WorkflowCommandResult findCommandResult(String commandType, IdempotencyFingerprint fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        IdempotentResult stored = idempotentResults.get(commandType + ":" + fingerprint.idempotencyKey());
        if (stored == null) {
            return null;
        }
        stored.requireSameFingerprint(fingerprint);
        return (WorkflowCommandResult) stored.result();
    }

    private void remember(String commandType, IdempotencyFingerprint fingerprint, Object result) {
        if (fingerprint != null) {
            idempotentResults.putIfAbsent(commandType + ":" + fingerprint.idempotencyKey(), new IdempotentResult(fingerprint, result));
        }
    }

    private <T> T withIdempotencyLock(
            String commandType,
            IdempotencyFingerprint fingerprint,
            Supplier<T> operation
    ) {
        if (fingerprint == null) {
            return operation.get();
        }
        Object lock = idempotencyLocks.computeIfAbsent(
                commandType + ":" + fingerprint.idempotencyKey(),
                ignored -> new Object());
        synchronized (lock) {
            return operation.get();
        }
    }

    private <T> T withInstanceLock(WorkflowInstanceId instanceId, Supplier<T> operation) {
        ReentrantLock lock = instanceLocks.computeIfAbsent(instanceId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private static IdempotencyFingerprint idempotencyFingerprint(StartWorkflowCommand command) {
        String idempotencyKey = command.metadata().idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return new IdempotencyFingerprint(
                idempotencyKey,
                command.workflowKey() + "|" + command.workflowVersion() + "|" + command.businessKey() + "|" + command.variables());
    }

    private static IdempotencyFingerprint idempotencyFingerprint(SignalWorkflowCommand command) {
        String idempotencyKey = command.metadata().idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return new IdempotencyFingerprint(
                idempotencyKey,
                command.instanceId() + "|" + command.signal());
    }

    private static IdempotencyFingerprint idempotencyFingerprint(RetryFailedStepCommand command) {
        String idempotencyKey = command.metadata().idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return new IdempotencyFingerprint(
                idempotencyKey,
                command.instanceId() + "|" + command.stepId());
    }

    private static IdempotencyFingerprint idempotencyFingerprint(CancelWorkflowCommand command) {
        String idempotencyKey = command.metadata().idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return new IdempotencyFingerprint(idempotencyKey, command.instanceId().toString());
    }

    private static IdempotencyFingerprint idempotencyFingerprint(ResumeWorkflowCommand command) {
        String idempotencyKey = command.metadata().idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return new IdempotencyFingerprint(idempotencyKey, command.instanceId().toString());
    }

    private record IdempotencyFingerprint(String idempotencyKey, String payloadFingerprint) {
    }

    private record IdempotentResult(IdempotencyFingerprint fingerprint, Object result) {
        private void requireSameFingerprint(IdempotencyFingerprint candidate) {
            if (!fingerprint.payloadFingerprint().equals(candidate.payloadFingerprint())) {
                throw new WorkflowIdempotencyConflictException(candidate.idempotencyKey());
            }
        }
    }

    private record LoopRoute(String targetNode, Map<String, Object> variables) {
    }

    private record SubWorkflowRoute(String targetNode, EventName eventName, Map<String, Object> variables) {
    }

    private final class InMemoryWorkflowEngineContext implements WorkflowEngineContext {
        @Override
        public List<WorkflowSnapshot> getWorkflows() {
            return List.copyOf(instances.values());
        }

        @Override
        public java.util.Optional<WorkflowSnapshot> getWorkflow(WorkflowInstanceId instanceId) {
            return java.util.Optional.ofNullable(instances.get(instanceId));
        }

        @Override
        public java.util.Optional<WorkflowSnapshot> getWorkflow(String workflowKey, String businessKey) {
            WorkflowInstanceId instanceId = instancesByBusinessKey.get(instanceKey(workflowKey, businessKey));
            return instanceId == null ? java.util.Optional.empty() : getWorkflow(instanceId);
        }
    }
}
