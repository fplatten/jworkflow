package org.jworkflow.engine;

import org.jworkflow.events.EventPublisher;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.model.WorkflowNodeType;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.model.WorkflowDefinitionRegistry;
import org.jworkflow.model.BranchConditionEvaluator;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowTimer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.time.Clock;

/**
 * Repository-neutral transition calculator. Each call uses isolated transient state and
 * returns its effects as data; it never owns durable workflow state.
 */
public final class WorkflowStateMachine {
    private final WorkflowDefinitionRegistry definitions;
    private final Map<String, StepHandler> stepHandlers;
    private final BranchConditionEvaluator conditions;
    private final Map<String, String> workflowStartEvents;
    private final Map<String, Object> listeners;
    private final Clock clock;

    /**
     * Returns an isolated calculator using the supplied exact definition revision.
     *  The original registry and other concurrent calculations are unchanged.
     * @param definition persisted definition for the instance being evaluated
     * @return calculator with that revision selected for its name and version
     * @throws NullPointerException if definition is null
     */
    public WorkflowStateMachine withDefinitionRevision(org.jworkflow.model.WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        WorkflowDefinitionRegistry selected = new WorkflowDefinitionRegistry();
        definitions.snapshot().values().stream().filter(d -> !d.key().equals(definition.key())).forEach(selected::register);
        selected.register(definition);
        return new WorkflowStateMachine(selected, stepHandlers, conditions, workflowStartEvents, listeners, clock);
    }

    /**
     * Constructs WorkflowStateMachine with the supplied collaborators and configuration.
     * @param definitions registry of selected workflow definitions
     * @param stepHandlers handlers indexed by declared action name
     * @param conditions declarative condition evaluator and registered predicates
     * @param workflowStartEvents mapping from event names to workflows started in supported runtime modes
     * @param listeners infrastructure listeners indexed by registered identity
     */
    public WorkflowStateMachine(WorkflowDefinitionRegistry definitions,
                                Map<String, StepHandler> stepHandlers,
                                BranchConditionEvaluator conditions,
                                Map<String, String> workflowStartEvents,
                                Map<String, Object> listeners) {
        this(definitions, stepHandlers, conditions, workflowStartEvents, listeners, Clock.systemUTC());
    }

    /**
     * Constructs WorkflowStateMachine with the supplied collaborators and configuration.
     * @param definitions registry of selected workflow definitions
     * @param stepHandlers handlers indexed by declared action name
     * @param conditions declarative condition evaluator and registered predicates
     * @param workflowStartEvents mapping from event names to workflows started in supported runtime modes
     * @param listeners infrastructure listeners indexed by registered identity
     * @param clock clock used for recorded times and lease/retry decisions
     * @throws NullPointerException if definitions, conditions, clock is null
     */
    public WorkflowStateMachine(WorkflowDefinitionRegistry definitions,
                                Map<String, StepHandler> stepHandlers,
                                BranchConditionEvaluator conditions,
                                Map<String, String> workflowStartEvents,
                                Map<String, Object> listeners,
                                Clock clock) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.stepHandlers = Map.copyOf(stepHandlers == null ? Map.of() : stepHandlers);
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.workflowStartEvents = Map.copyOf(workflowStartEvents == null ? Map.of() : workflowStartEvents);
        this.listeners = listeners == null ? Map.of() : listeners;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Calculates the initial snapshot, events and timers without persisting them.
     * @param command command to validate and execute
     * @return the resulting workflow transition result&lt;start workflow result&gt;
     */
    public WorkflowTransitionResult<StartWorkflowResult> start(StartWorkflowCommand command) {
        Session session = session(null, List.of());
        StartWorkflowResult result = session.engine.start(command);
        WorkflowSnapshot normalized = withLockVersion(result.snapshot(), 0);
        result = new StartWorkflowResult(result.commandId(), result.workflowInstanceId(), result.workflowKey(),
                result.workflowVersion(), result.businessKey(), result.correlationId(), result.acceptedAt(),
                normalized, result.emittedEventIds(), result.idempotentRepeat());
        return new WorkflowTransitionResult<>(result,
                new WorkflowMutation(null, normalized, session.events, List.of(), session.engine.allTimers()));
    }

    /**
     * Calculates signal effects from the supplied snapshot and timer state; the caller owns atomic persistence.
     * @param current current persisted workflow snapshot
     * @param timers current timer occurrences associated with the instance
     * @param command command to validate and execute
     * @return the resulting workflow transition result&lt;workflow command result&gt;
     */
    public WorkflowTransitionResult<WorkflowCommandResult> signal(WorkflowSnapshot current,
                                                                   List<WorkflowTimer> timers,
                                                                   SignalWorkflowCommand command) {
        return command(current, timers, engine -> engine.signal(command));
    }

    /**
     * Side-effect-free event eligibility check used before durable routing mutates a snapshot.
     * @param snapshot point-in-time workflow snapshot
     * @param event event to deliver or inspect
     * @return true when the condition described above holds; false otherwise
     * @throws NullPointerException if snapshot, event is null
     */
    public boolean accepts(WorkflowSnapshot snapshot, WorkflowEvent event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        if (snapshot.status() != WorkflowStatus.RUNNING && snapshot.status() != WorkflowStatus.WAITING) return false;
        var definition = definitions.find(snapshot.workflowKey(), snapshot.workflowVersion()).orElse(null);
        if (definition == null || !definition.revision().equals(snapshot.workflowRevision())) return false;
        WorkflowNode node = definition.nodes().get(snapshot.state());
        if (node == null) return false;
        String eventName = event.eventName().value();
        if (node.type() == WorkflowNodeType.WAIT) {
            return node.waitDefinition().eventName().value().equals(eventName);
        }
        if (node.type() != WorkflowNodeType.STEP) return false;
        if (node.transitions().stream().anyMatch(transition -> transition.emittedEvent() != null
                && transition.emittedEvent().value().equals(eventName))) return true;
        return snapshot.state().equals(definition.startNode())
                && eventName.equals(definition.metadata().get("startEvent"));
    }

    /**
     * Calculates a failed-step retry and its durable effects; the caller owns commit and side-effect
     * reconciliation.
     * @param current current persisted workflow snapshot
     * @param timers current timer occurrences associated with the instance
     * @param command command to validate and execute
     * @return the resulting workflow transition result&lt;workflow command result&gt;
     */
    public WorkflowTransitionResult<WorkflowCommandResult> retry(WorkflowSnapshot current,
                                                                  List<WorkflowTimer> timers,
                                                                  RetryFailedStepCommand command) {
        return command(current, timers, engine -> engine.retryFailedStep(command));
    }

    /**
     * Calculates cancellation and timer changes without writing repositories.
     * @param current current persisted workflow snapshot
     * @param timers current timer occurrences associated with the instance
     * @param command command to validate and execute
     * @return the resulting workflow transition result&lt;workflow command result&gt;
     */
    public WorkflowTransitionResult<WorkflowCommandResult> cancel(WorkflowSnapshot current,
                                                                   List<WorkflowTimer> timers,
                                                                   CancelWorkflowCommand command) {
        return command(current, timers, engine -> engine.cancel(command));
    }

    /**
     * Calculates resume effects from the supplied persisted state.
     * @param current current persisted workflow snapshot
     * @param timers current timer occurrences associated with the instance
     * @param command command to validate and execute
     * @return the resulting workflow transition result&lt;workflow command result&gt;
     */
    public WorkflowTransitionResult<WorkflowCommandResult> resume(WorkflowSnapshot current,
                                                                   List<WorkflowTimer> timers,
                                                                   ResumeWorkflowCommand command) {
        return command(current, timers, engine -> engine.resume(command));
    }

    /**
     * Calculates effects for a leased timer. The caller must validate/fence the acquisition and persist all
     * effects atomically.
     * @param current current persisted workflow snapshot
     * @param timers current timer occurrences associated with the instance
     * @param claimedTimer timer and acquisition generation currently being processed
     * @param now clock instant used for eligibility or retry calculation
     * @return the resulting workflow transition result&lt;workflow timer&gt;
     * @throws NullPointerException if current, claimedTimer is null
     */
    public WorkflowTransitionResult<WorkflowTimer> fireTimer(WorkflowSnapshot current,
                                                              List<WorkflowTimer> timers,
                                                              WorkflowTimer claimedTimer,
                                                              Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(claimedTimer, "claimedTimer");
        ArrayList<WorkflowTimer> executable = new ArrayList<>();
        for (WorkflowTimer timer : timers == null ? List.<WorkflowTimer>of() : timers) {
            if (timer.timerId().equals(claimedTimer.timerId())) {
                executable.add(new WorkflowTimer(timer.timerId(), timer.workflowInstanceId(), timer.stepName(),
                        timer.dueAt(), timer.targetNode(), timer.emittedEvent(),
                        org.jworkflow.model.WorkflowTimerStatus.PENDING, timer.attemptCount(), timer.nextAttemptAt(),
                        null, null, timer.createdAt(), timer.updatedAt()));
            }
        }
        Session session = session(current, executable);
        WorkflowTimer fired = session.engine.fireDueTimers(now).stream()
                .filter(timer -> timer.timerId().equals(claimedTimer.timerId()))
                .findFirst().orElseThrow(() -> new WorkflowInvalidStateException(
                        "Claimed timer is no longer applicable to workflow " + current.instanceId()));
        WorkflowSnapshot next = withLockVersion(session.engine.snapshot(current.instanceId()), current.lockVersion() + 1);
        return new WorkflowTransitionResult<>(fired,
                new WorkflowMutation(current, next, session.events, timers, session.engine.allTimers()));
    }

    private WorkflowTransitionResult<WorkflowCommandResult> command(
            WorkflowSnapshot current, List<WorkflowTimer> timers,
            java.util.function.Function<InMemoryWorkflowEngine, WorkflowCommandResult> operation) {
        Objects.requireNonNull(current, "current");
        List<WorkflowTimer> before = timers == null ? List.of() : List.copyOf(timers);
        Session session = session(current, before);
        WorkflowCommandResult result = operation.apply(session.engine);
        WorkflowSnapshot normalized = withLockVersion(result.snapshot(), current.lockVersion() + 1);
        result = new WorkflowCommandResult(result.commandId(), result.workflowInstanceId(), result.status(), normalized,
                result.emittedEventIds(), result.eventStatusAttemptIds(), result.idempotentRepeat());
        return new WorkflowTransitionResult<>(result,
                new WorkflowMutation(current, normalized, session.events, before, session.engine.allTimers()));
    }

    private Session session(WorkflowSnapshot snapshot, List<WorkflowTimer> timers) {
        ArrayList<WorkflowEvent> staged = new ArrayList<>();
        EventPublisher collector = staged::add;
        InMemoryWorkflowEngine engine = InMemoryWorkflowEngine.create(definitions, collector, stepHandlers,
                conditions, workflowStartEvents, listeners, clock);
        if (snapshot != null) engine.restore(snapshot, timers);
        return new Session(engine, staged);
    }

    /**
     * Isolated transient engine session collecting events for one transition calculation.
     * @param engine engine whose lifetime remains the caller's responsibility
     * @param events workflow events in their supplied order
     */
    private record Session(InMemoryWorkflowEngine engine, List<WorkflowEvent> events) { }

    private static WorkflowSnapshot withLockVersion(WorkflowSnapshot snapshot, long version) {
        return new WorkflowSnapshot(snapshot.instanceId(), snapshot.workflowKey(), snapshot.workflowVersion(),
                snapshot.workflowRevision(), snapshot.businessKey(), snapshot.correlationId(), snapshot.state(),
                snapshot.status(), snapshot.variables(), version, snapshot.createdAt(), snapshot.updatedAt());
    }
}
