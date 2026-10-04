# Changelog

All notable changes are recorded here. The project has not been released yet; versions follow
[Semantic Versioning](https://semver.org/) once it is. See [upgrading](docs/guide/upgrading.md) for migration steps.

## Unreleased (0.1.0-SNAPSHOT)

### Security

- The Groovy DSL compiler no longer runs Groovy's compile phases, which applied AST transforms: an annotation such as
  `@ASTTest` could run code while a definition was loading. Sources are now only parsed and interpreted, and every
  annotation is rejected.

### Fixed

- SQLite: an idempotent repeat now returns the snapshot and event ids recorded when the command first ran, as on
  PostgreSQL. It used to return the workflow's current state.
- Inbox and outbox messages dead-lettered because their worker's lease kept expiring are now reported. Previously
  only processing or publication failures were, and outbox dead letters had no event at all.
- Timestamps written by the engine now come from the injected `Clock`. Command-result `created_at`, workflow
  definition `created_at`/`updated_at`, and timer `created_at`/`updated_at` (scheduled, retried, fired and cancelled)
  used the system clock even when a different clock was configured. Timer due times and lease decisions already used
  the injected clock.
- Durable transitions no longer start a background thread in their temporary engine, which could fire a retry timer
  and run a step handler a second time.
- Expired inbox, outbox and timer leases now count as attempts; inbox and outbox messages that exhaust their attempts
  this way are dead-lettered instead of being redelivered forever.
- PostgreSQL: a timer firing and a command on the same workflow no longer deadlock (the timer path locks the workflow
  row first).
- Commands no longer fail with `StaleWorkflowClaimException` when a poller has just claimed one of the workflow's
  timers; only changed timers are written.
- Leaving a step cancels its claimed and retry-pending timers too; timers that fire after their workflow moved on are
  cancelled instead of retried forever.
- Inbox, outbox and timer pollers survive JVM `Error`s and log failures; one failing item no longer abandons the rest
  of its batch.
- Sub-workflows: only a completed child takes the success route; waiting children are cancelled; recursion is bounded;
  durable engines persist the child instance and never write timers for it.
- Manual retry works for steps whose automatic retries are used up.
- Cycles made only of automatic routing nodes are rejected instead of spinning forever.
- In-memory engine: events and timers can no longer be stranded when the event loop shuts down, and an `Error` no
  longer stops the loop.
- SQLite timestamps are stored with a fixed-width fraction, so timers, leases and event paging compare in time order.
- `Long`, `BigDecimal`, `BigInteger`, `Float`, `Short` and `Byte` workflow variables keep their type and exact value
  after a save and reload.
- One failing fan-out target no longer rolls back delivery to the others.
- `engine.publish(event)` has the same effect on every engine. The durable engines now start a workflow whose start
  event it is (`startWhen` / `start when:` or `startWorkflowOn`) and deliver it to waiting instances by the
  definition's `correlateBy` field, as the in-memory engine did; they used to reject any event without a
  `workflowKey` header. The in-memory engine's `publish` now returns once the event, and every event its listeners
  publish, has been processed, and throws the failure if processing fails. It used to return at once and only log
  failures, so a broken listener or a missing correlation field left the workflow silently stalled.
- A Java-builder step that only names a listener gets its step name as its action, as in the DSL. It used to have no
  action, and running it failed with a `NullPointerException`.
- `pendingWaits` finds waits beyond the first page; `findByCorrelationId` ignores finished instances.
- Header and attribute redaction is case-insensitive.
- Inbox acceptance ignores caller-supplied status, attempts and claims.
- Loop counters reset when the loop exits.
- Durable and in-memory engines accept the same events.
- Bounded memory: finished timers are no longer kept, idempotency locks are released, at most 10,000 finished
  instances are retained in memory, and listener-failure numbering no longer keeps a growing map.

### Changed

- Validation rejects `PARALLEL`/`INCLUSIVE` gateways (they ran as exclusive) and timeouts with neither a target nor an
  event.
- The DSL rejects `sla` (it was ignored), checks that a step's `on` event is actually delivered to it, and reports a
  misspelled `start at:` node.
- Emit-only timeouts now emit their event and keep the workflow in place; the DSL accepts `timeout "...", emit: "..."`.
- `WorkflowMutation` gains `createdInstances`; the previous constructor remains.
- **Supported API defined.** Engine plumbing, persistence ports, inbox/outbox processing services, event-status
  storage, the in-process event bus and other implementation types moved to `org.jworkflow.internal.*` packages,
  which are not supported API and may change in any release. See
  [Supported API](docs/guide/supported-api.md) and [upgrading](docs/guide/upgrading.md#package-moves-for-010).
- `WorkflowEngineBuilder.build()`, `buildAndSetInstance()` and `WorkflowEngine.create(...)` no longer declare the
  checked `ClassNotFoundException`; a missing `jworkflow-jdbc` module or JDBC driver now raises
  `IllegalStateException` with the same message.
- `WorkflowEngine.builder()` is the only supported way to create engines: the `JdbcWorkflowEngine.create(...)`
  factories and `WorkflowEngineBuilder.persistence(...)` are no longer public, and `JdbcWorkflowPersistence`,
  `JdbcTransactionManager` and `JdbcInboxEventAdapter` are package-private.
- The process-wide engine is managed only through `WorkflowEngine.instance()`, `setInstance()`, `replaceInstance()`
  (new) and `clearInstance()`; the duplicate `WorkflowEngines` class moved to an internal package.
- `PendingTimerView.from(...)` was removed (it took an internal timer type).
- The `HelloWorld` example moved from `jworkflow-core` to `jworkflow-example` (`org.jworkflow.example.HelloWorld`),
  so the core jar no longer ships example code.

### Added

- `WorkflowLifecycleEventType.OUTBOX_DEAD_LETTERED` (counted as `jworkflow.outbox.dead_lettered`), emitted when an
  outbox message runs out of publication attempts or leases. Outbox lifecycle events and `INBOX_DEAD_LETTERED` carry
  a `messageId` attribute; dead-letter events also carry `failureCategory` (`publication_failed`,
  `inbox_processing_failed` or `lease_expired`).
- `WorkflowQueryService.deadLetteredOutbox(destination, afterMessageId, limit)`: page through dead-lettered outbox
  messages, optionally for one destination.

- SQLite migrations V7 (fixed-width timestamps) and V8, and PostgreSQL migration V3: an index on
  `workflow_timer(workflow_instance_id)`.
- `InboxMessage.asReceived()`, `InboxProcessingService.partiallyRouted(...)` and savepoint-scoped
  `JdbcTransactionManager.inSavepoint(...)`.
- User guides under `docs/guide/`: operations, threading and lifecycle, DSL reference, persisted data format and
  upgrading.
- POSIX equivalents of the PowerShell example scripts.

### Build

- CI runs on pushes to `main` and on pull requests, cancels superseded runs, and no longer repeats the ordinary suite
  in every PostgreSQL job.
- The POM's project URL and SCM point to `github.com/fplatten/jworkflow`; the Sonar server URL is no longer
  hard-coded.
