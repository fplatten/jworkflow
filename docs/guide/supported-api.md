# Supported API

From 0.1.0, jworkflow follows [Semantic Versioning](https://semver.org/) for the types listed here. Breaking changes to
them are recorded in the [changelog](../../CHANGELOG.md) and the [upgrading guide](upgrading.md).

**Everything in `org.jworkflow.internal.*` is internal.** Those types are public only so that `jworkflow-core` and
`jworkflow-jdbc` can share them. They may change or disappear in any release, including patch releases. If you need
something that is only available internally, please open an issue describing the use case.

## Where to start

| You want to… | Use |
|---|---|
| Create an engine (in-memory, SQLite or PostgreSQL) | `WorkflowEngine.builder()` → `WorkflowEngineBuilder` |
| Define a workflow in Java | `WorkflowDefinitionBuilder`, or `WorkflowDefinition` with `WorkflowNode` and the `model` types |
| Define a workflow in the Groovy DSL | `ClasspathWorkflowDefinitionSource` / `FileSystemWorkflowDefinitionSource` passed to `builder.definitions(...)`, or `GroovyWorkflowDslCompiler` directly |
| Run step logic | `StepHandler`, `StepContext`, `StepResult` |
| Start, signal, cancel, resume, retry | `WorkflowEngine` methods, or the `*WorkflowCommand` types with `WorkflowCommandMetadata` |
| Read workflow state | `WorkflowEngine.snapshot(...)`, `WorkflowEngine.queries()` → `WorkflowQueryService` |
| Integrate with messaging | `JdbcInboxApplication`, `JdbcOutboxApplication`, `DestinationPublisher`, `InboxEventTranslator` |
| Observe the engine | `WorkflowLifecycleObserver` and the provided observers |

## Packages

### `org.jworkflow.engine`: engines, commands and step code
`WorkflowEngine`, `WorkflowEngineBuilder`, `WorkflowEngineProperties`, `WorkflowEngineContext`;
`StartWorkflowCommand`, `SignalWorkflowCommand`, `CancelWorkflowCommand`, `ResumeWorkflowCommand`,
`RetryFailedStepCommand`, `WorkflowCommandMetadata`, `StartWorkflowResult`, `WorkflowCommandResult`,
`WorkflowCommandStatus`; `StepHandler`, `StepContext`, `StepResult`; exceptions `WorkflowCommandException`,
`WorkflowCommandConflictException`, `WorkflowIdempotencyConflictException`, `WorkflowDefinitionNotFoundException`,
`WorkflowInstanceNotFoundException`, `WorkflowInvalidStateException`, `WorkflowValidationException`,
`WorkflowInfrastructureException`.

### `org.jworkflow.model`: workflow definitions and runtime state
`WorkflowDefinition`, `WorkflowNode`, `WorkflowNodeType`, `WorkflowTransition`, `WaitDefinition`,
`TimeoutDefinition`, `RetryPolicy`, `BranchCondition`, `BranchConditionEvaluator`, `GatewayType`, `ForkDefinition`,
`JoinDefinition`, `LoopDefinition`, `SubWorkflowDefinition`, `ListenerInvocation`, `ListenerArgument`,
`DefinitionValidationResult`, `DefinitionValidationError`; `WorkflowInstanceId`, `WorkflowSnapshot`,
`WorkflowStatus`, `WorkflowSignal`, `WorkflowTimerStatus`.

### `org.jworkflow.definition`: building and loading definitions
`WorkflowDefinitionBuilder`, `WorkflowDefinitionSource`, `ClasspathWorkflowDefinitionSource`,
`FileSystemWorkflowDefinitionSource`, `WorkflowDefinitionText`, `WorkflowDefinitionSourceMetadata`,
`DefinitionActivationException`, `DefinitionActivationResult`, `DefinitionActivationStatus`,
`ActivatedWorkflowDefinition`.

### `org.jworkflow.dsl`: the Groovy DSL compiler
`GroovyWorkflowDslCompiler`, `DslCompilerOptions`, `DslCompilationException`, `DslDiagnostic`,
`DslDiagnosticCategory`, `DslSourcePosition`. See the [DSL reference](dsl-reference.md).

### `org.jworkflow.events`: events and integration metadata
`WorkflowEvent`, `EventName`, `EventMetadata`, `EventMessage`, `IntegrationEvent`, `Events`, `EventPublisher`,
`CorrelationId`, `CausationId`, `TraceId`.

### `org.jworkflow.routing`: routing events to workflow instances
`WorkflowEventRouter` (implemented by `WorkflowEngine`), `WorkflowEventRoute`, `WorkflowRoutingResult`,
`WorkflowRoutingOutcome`, `WorkflowRoutingMode`, `WorkflowRoutingException`, `AmbiguousWorkflowRouteException`,
`InvalidWorkflowRouteException`.

### `org.jworkflow.query`: read models
`WorkflowQueryService`, `WorkflowDetail`, `WorkflowInstanceSummary`, `WorkflowTimeline`, `WorkflowTimelineEntry`,
`PendingWaitView`, `PendingTimerView`, `OutboxStateView`, `WorkflowProjection`.

### `org.jworkflow.inbox` and `org.jworkflow.outbox`: transactional messaging
Inbox: `InboxMessage`, `InboxMessageStatus`, `InboxEventTranslator`, `InboxInsertResult`, `InboxAttempt`,
`ReprocessInboxMessageCommand`, and the exceptions `InboxStateException`, `InboxDuplicateException`,
`InboxClaimException`, `InboxReprocessingException`.
Outbox: `OutboxMessage`, `OutboxMessageStatus`, `OutboxAttempt`, `DestinationPublisher`, `OutboxRouter`,
`RepublishOutboxMessageCommand`, and the exceptions `OutboxPublicationException`, `OutboxRetryException`,
`OutboxDeadLetterException`, `OutboxRepublishException`, `OutboxClaimException`.

### `org.jworkflow.observability`: lifecycle events and metrics
`WorkflowLifecycleObserver`, `WorkflowLifecycleEvent`, `WorkflowLifecycleEventType`,
`CompositeWorkflowLifecycleObserver`, `MetricsWorkflowLifecycleObserver`, `SystemLoggerWorkflowLifecycleObserver`,
`WorkflowMetrics`, `LifecycleObservationErrorHandler`.

### `org.jworkflow.security`: what event data is persisted or published
`EventCapturePolicy`, `CaptureAllEventPolicy`, `FilteringEventCapturePolicy`, `MetadataOnlyEventPolicy`.

### `org.jworkflow.persistence`: persistence exceptions
`WorkflowPersistenceException`, `WorkflowOptimisticLockException`, `PersistenceSerializationException`,
`PersistenceConstraintException`, `PersistenceTimeoutException`, `StaleWorkflowClaimException`. The persistence
interfaces themselves are internal in 0.1.0.

### `org.jworkflow.application`
`Command`, the common type of the command objects.

### `org.jworkflow.jdbc` (module `jworkflow-jdbc`): durable engines
`JdbcWorkflowEngine` (returned by the builder for `SQLITE`/`POSTGRESQL`), `JdbcInboxApplication`,
`JdbcOutboxApplication`, `JdbcTransactionException`, and `JdbcJsonCodec`'s documented format constants (see
[persisted data format](persisted-data-format.md)). Create engines with `WorkflowEngine.builder()`, not through
JDBC classes.

## How the boundary is enforced

`ApiBoundaryTest` in `jworkflow-jdbc` fails the build if any type listed here exposes an internal type in a public or
protected signature, supertype or field.
