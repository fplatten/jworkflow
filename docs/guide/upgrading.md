# Upgrading

jworkflow has not had a public release yet; these notes cover moving an existing database or application from an
earlier `0.1.0-SNAPSHOT` build to the current one. See the [changelog](../../CHANGELOG.md) for the full list of changes.

## Procedure

1. **Back up the database.** SQLite migration V7 rewrites every timestamp column and cannot be undone.
2. **Stop every worker and engine** that uses the database. Running old and new builds side by side is not supported:
   older builds reject the new typed-number JSON format, and lease handling has changed.
3. **Apply the migrations** with your chosen owner: the built-in initializer (`.initialize(true)`), Flyway or Liquibase.
   See [operations](operations.md#schema-and-migrations).
4. **Check your workflow definitions** against the validation changes below. `build()` validates every definition
   given to the builder, so an application that registers an affected definition at startup fails to start until the
   definition is fixed. Definitions already stored in the database are not re-validated, and instances running on
   them continue.
5. **Start the new build.**

## Schema changes

| Database | Version | Change | Notes |
| --- | --- | --- | --- |
| SQLite | V7 | Fixed-width timestamps | Rewrites all timestamp columns; time depends on table size. |
| SQLite | V8 | Index on `workflow_timer(workflow_instance_id)` | Additive. |
| PostgreSQL | V3 | Index on `workflow_timer(workflow_instance_id)` | Plain `create index`; it locks writes to `workflow_timer` while it builds. Run it during the upgrade window. |

## Definitions that are now rejected

| Construct | Validation code | Replace with |
| --- | --- | --- |
| Gateway of type `PARALLEL` or `INCLUSIVE` | `gateway.type.unsupported` | `EXCLUSIVE`; these types always behaved as exclusive. |
| Timeout with neither a target nor an event | `timeout.action.required` | `timeout "PT5M", goTo: "..."` and/or `emit: "..."` |
| Cycle made only of gateways, forks, sub-workflow calls and loop exits | `routing.cycle` | Put a step or wait on the cycle. |
| DSL `sla ..., onBreach: ...` | DSL grammar error | `timeout "PT5M", emit: "..."` |
| DSL step `on "event"` that nothing delivers | DSL grammar error | Fix the event name, or remove `on`. |
| DSL `start at:` naming a missing node | `start.unknown` | Fix the node name. |

## Behaviour changes

- **Sub-workflows** take the success route only when the child completed. A child that would have to wait is
  cancelled and the parent takes the failure route. Nesting is limited to 16 levels.
- **Manual retry** (`retryFailedStep`) now runs the step immediately and gives it a fresh set of automatic retries.
  It used to refuse steps whose retry policy was used up.
- **Emit-only timeouts** now fire: they emit their event and leave the workflow where it is. They used to do nothing.
- **Loop counters** reset when the loop exits, so a loop entered again gets its full iteration count.
- **Event acceptance** is the same in every engine: the durable engines now also accept a step's action-derived
  success event (action `payment.charge` accepts `payment.charged`).
- **Numbers** in workflow variables keep their Java type (`Long`, `BigDecimal`, …) after a save and reload. Code that
  relied on getting an `Integer` or `Double` back for these types must now expect the original type.
- **Expired leases count as attempts.** Inbox and outbox messages whose worker repeatedly crashes are dead-lettered
  after `max-attempts` instead of being redelivered forever.
- **Inbox acceptance** ignores the status, attempts and claim fields supplied by the caller; messages always start as
  RECEIVED.
- **Header and attribute redaction** in `FilteringEventCapturePolicy` ignores case.
- **`findByCorrelationId`** ignores finished instances; only two *active* matches are ambiguous.
- **In-memory engine** keeps only the 10,000 most recently finished instances; older ones are evicted with their
  idempotency results.
- **SQLite repeats return the original result.** An idempotent repeat on SQLite returns the snapshot and event ids
  from when the command first ran, as PostgreSQL already did, instead of the current state. Commands recorded before
  the upgrade keep the old behaviour.
- **More dead-letter events.** `INBOX_DEAD_LETTERED` is now also emitted when lease expiry exhausts a message, and
  the new `OUTBOX_DEAD_LETTERED` covers outbox messages. A `switch` over `WorkflowLifecycleEventType` without a
  `default` branch needs a case for `OUTBOX_DEAD_LETTERED`.
- **`publish` behaves the same on every engine.** On SQLite and PostgreSQL, an event without a `workflowKey` header
  now starts workflows whose start event it is and is delivered by the definition's `correlateBy` field, instead of
  throwing `InvalidWorkflowRouteException`. Events that carry a `workflowKey` header or a workflow instance id are
  routed exactly as before.
- **In-memory `publish` waits.** It returns after the event, and the events its listeners publish, have been
  processed, and throws the first failure (for example a listener exception or a missing correlation field). Code
  that polled for the outcome still works. Do not hold a lock in the publishing thread that a listener needs. Events
  published from a listener or step on the engine's own thread are queued, so they never wait.
- **Java-builder listener steps** without `action(...)` now use the step name as their action, as the DSL does. The
  definition gets a new revision; instances already running keep their stored revision.
- **Recorded timestamps follow the injected `Clock`.** Command results, workflow definitions and timers now record
  `created_at`/`updated_at` from the clock passed to `WorkflowEngine.builder().clock(...)` instead of the system clock.
  Engines that use the default clock see no difference. Schema migration history still records system time.

## API changes

- `InboxMessage.asReceived()` is new. (`WorkflowMutation.createdInstances` and
  `InboxProcessingService.partiallyRouted(...)` were also added, but both types are internal from 0.1.0; see below.)
- `WorkflowEngineBuilder.build()`, `buildAndSetInstance()` and `WorkflowEngine.create(...)` no longer declare
  `ClassNotFoundException`. A missing `jworkflow-jdbc` module or JDBC driver raises `IllegalStateException` with the
  same message; remove `catch (ClassNotFoundException ...)` blocks around `build()`.
- `WorkflowEngine.replaceInstance(...)` is new and replaces `WorkflowEngines.replaceInstance(...)`. Use
  `WorkflowEngine.instance()`, `setInstance()` and `clearInstance()` instead of the `WorkflowEngines` equivalents.
- No longer public: the `JdbcWorkflowEngine.create(...)` factories (use `WorkflowEngine.builder()`),
  `WorkflowEngineBuilder.persistence(...)`, `JdbcWorkflowEngine.transactionManager()`, and the classes
  `JdbcWorkflowPersistence`, `JdbcTransactionManager` and `JdbcInboxEventAdapter`.
- `PendingTimerView.from(...)` was removed; views come from `WorkflowQueryService`.
- `WorkflowQueryService.deadLetteredOutbox(...)` is new. Custom implementations of `WorkflowQueryService` must add it.

### Package moves for 0.1.0

0.1.0 defines the [supported API](supported-api.md). Implementation types moved to `org.jworkflow.internal.*`
packages. They stay public only so the jworkflow modules can share them; they are **not supported** and may change
in any release. If you imported one of them, switch to the supported API listed in the guide, or open an issue
describing the use case.

- `org.jworkflow.application` → `org.jworkflow.internal.application`: `RetryBackoffPolicy`
- `org.jworkflow.definition` → `org.jworkflow.internal.definition`: `WorkflowDefinitionActivationService`
- `org.jworkflow.engine` → `org.jworkflow.internal.engine`: `InMemoryWorkflowEngine`, `WorkflowContextSnapshot`, `WorkflowEngines`, `WorkflowExecutionContext`, `WorkflowMutation`, `WorkflowStateMachine`, `WorkflowStepContext`, `WorkflowTransitionResult`
- `org.jworkflow.events` → `org.jworkflow.internal.events`: `EventBus`, `EventDeliveryFailure`, `EventListener`, `EventStatusAttempt`, `EventStatusRecordingErrorHandler`, `EventStatusRepository`, `EventStatusScope`, `EventStatusValue`, `EventSubscriber`, `EventSubscriberErrorHandler`, `EventSubscriberException`, `EventSubscription`, `InMemoryEventBus`, `NoOpEventPublisher`, `SubscribeTo`, `WorkflowEventPublisher`
- `org.jworkflow.inbox` → `org.jworkflow.internal.inbox`: `ExponentialInboxRetryPolicy`, `InboxAcceptanceService`, `InboxClaim`, `InboxCommandDispatcher`, `InboxProcessingResult`, `InboxProcessingService`, `InboxReprocessingService`, `InboxRoutingTranslator`
- `org.jworkflow.model` → `org.jworkflow.internal.model`: `DefinitionValidator`, `ImmutableData`, `WorkflowDefinitionRegistry`, `WorkflowTimer`, `WorkflowTimerAttempt`
- `org.jworkflow.observability` → `org.jworkflow.internal.observability`: `NoOpWorkflowLifecycleObserver`, `SafeWorkflowLifecycleObserver`, `WorkflowEventLifecycleAdapter`
- `org.jworkflow.outbox` → `org.jworkflow.internal.outbox`: `OutboxClaim`, `OutboxEnqueueService`, `OutboxPublisherService`, `OutboxRepublishingService`, `OutboxRoutingService`
- `org.jworkflow.persistence` → `org.jworkflow.internal.persistence`: `ActiveWorkflowCursor`, `CommandResultRecord`, `CommandResultRepository`, `InboxRepository`, `OutboxRepository`, `WorkflowDefinitionRepository`, `WorkflowEventRepository`, `WorkflowInstanceRepository`, `WorkflowPersistence`, `WorkflowTimerRepository`, `WorkflowTransaction`, `WorkflowTransactionManager`, `WorkflowTransactionalWork`
- `org.jworkflow.query` → `org.jworkflow.internal.query`: `PersistenceWorkflowQueryService`, `WorkflowProjectionPublisher`
- `org.jworkflow.routing` → `org.jworkflow.internal.routing`: `RouteWorkflowEventCommand`
- `org.jworkflow.security` → `org.jworkflow.internal.security`: `SafeEventCapturePolicy`
- `org.jworkflow.examples.HelloWorld` → `org.jworkflow.example.HelloWorld`, now in the `jworkflow-example` module.

The persistence exceptions (`WorkflowPersistenceException`, `WorkflowOptimisticLockException`,
`PersistenceSerializationException`, `PersistenceConstraintException`, `PersistenceTimeoutException`,
`StaleWorkflowClaimException`) stay in `org.jworkflow.persistence`.
- `JdbcJsonCodec.TYPED_NUMBERS_VERSION` is new.
- `WorkflowTimer.fired(Instant)` and `WorkflowTimer.canceled(Instant)` are new; the no-argument forms remain and use
  the system clock.
