# Operating the durable engine

This guide covers the SQLite and PostgreSQL engines (`jworkflow-jdbc`): background workers, settings, schema
migrations, leases and recovery. The in-memory engine has no durable workers; see
[threading and lifecycle](threading-and-lifecycle.md).

## Background workers

A durable engine has three kinds of queued work. Only the timer worker starts on its own.

| Worker | Started by | Thread name | Stopped by |
| --- | --- | --- | --- |
| Timers (timeouts, step retries) | `build()`, unless `timerPolling(false)` | `jworkflow-jdbc-timers-*` | `engine.close()` |
| Inbox (accepted external messages) | `engine.inbox(translator).start()` | `jworkflow-jdbc-inbox-*` | `inbox.close()` |
| Outbox (publishing workflow events) | `engine.outbox(publishers).start()` | `jworkflow-jdbc-outbox-*` | `outbox.close()` |

`inbox(...)` and `outbox(...)` are methods of `JdbcWorkflowEngine`. `WorkflowEngine.builder().build()` returns the
`WorkflowEngine` interface, so cast the result when the engine type is SQLITE or POSTGRESQL. The builder's
`inbox*` and `outbox*` settings configure these workers; they have no effect until a worker is created.

```java
try (JdbcWorkflowEngine engine = (JdbcWorkflowEngine) WorkflowEngine.builder()
        .type(WorkflowEngine.Type.POSTGRESQL)
        .jdbcUrl(url).username(user).password(password)
        .definitions(definitionSource)
        .outboxRetry(5, 1_000, 60_000)
        .build();
     JdbcOutboxApplication outbox = engine.outbox(Map.of(
             "workflow.events", message -> broker.send(message)));
     JdbcInboxApplication inbox = engine.inbox(message -> List.of(translate(message)))) {
    outbox.start();
    inbox.start();
    // ... the application runs; accept external messages with inbox.accept(...)
}
```

Close the workers before the engine. Try-with-resources closes them in reverse order of declaration, as above.
Closing the engine does not stop workers created from it.

Instead of `start()`, a host can drive the inbox and outbox workers itself by calling `pollOnce()`, for example from
its own scheduler; each call processes at most one batch. Timers have no public manual trigger: an engine built with
`timerPolling(false)` does not fire timers, so at least one process sharing the database must keep timer polling on.

### Outbox destinations

Every workflow event is written to the outbox in the same transaction as the workflow change. Each event is routed to
one or more destination names:

- `outbox.destination.<event.name>` lists destinations for one event name (comma-separated);
- otherwise `outbox.default-destination` applies, which defaults to `workflow.events`.

`engine.outbox(Map<String, DestinationPublisher>)` maps each destination name to a publisher. A message for a
destination without a publisher fails and is retried, then dead-lettered.

### Delivery guarantees

- Outbox publication happens outside database transactions and is **at least once**. A crash after the destination
  accepts a message but before the result is recorded publishes it again. Deduplicate at the destination using the
  message's idempotency key.
- Inbox messages are deduplicated by `(sourceSystem, externalEventId)`. An accepted message always starts as
  RECEIVED, whatever status the caller set.
- A fan-out route keeps successful deliveries when one target fails, then retries the message. Already-delivered
  targets are recognised as repeats and skipped.

## Settings

Typed builder methods validate bounds; `setting(key, value)` and `jworkflow.setting.<key>` properties set the same keys.

| Key | Default | Builder method |
| --- | --- | --- |
| `recovery.timer-poll-enabled` | `true` | `timerPolling` |
| `recovery.timer-poll-interval-ms` | 100 | `timerPollIntervalMillis` |
| `recovery.lease-ms` | 30000 | `recoveryLeaseMillis` |
| `recovery.timer-batch-size` | 32 | `timerBatchSize` |
| `recovery.timer-retry-delay-ms` | 1000 | `timerRetryDelayMillis` |
| `recovery.lazy-definition-validation` | `true` | `lazyDefinitionValidation` |
| `recovery.startup-validation-batch-size` | 500 | `startupValidationBatchSize` |
| `routing.maximum-candidates` | 100 | `eventRoutingMaximumCandidates` |
| `inbox.poll-interval-ms` / `outbox.poll-interval-ms` | 250 | `inboxPollingMillis` / `outboxPollingMillis` |
| `inbox.lease-ms` / `outbox.lease-ms` | 30000 | `inboxClaimLeaseMillis` / `outboxClaimLeaseMillis` |
| `inbox.batch-size` / `outbox.batch-size` | 32 | `inboxBatchSize` / `outboxBatchSize` |
| `inbox.max-attempts` / `outbox.max-attempts` | 5 | `inboxRetry` / `outboxRetry` |
| `inbox.retry-initial-ms` / `outbox.retry-initial-ms` | 1000 | `inboxRetry` / `outboxRetry` |
| `inbox.retry-maximum-ms` / `outbox.retry-maximum-ms` | 60000 | `inboxRetry` / `outboxRetry` |
| `outbox.default-destination` | `workflow.events` | — |
| `postgres.command-lock-timeout-ms` | 5000 | — |
| `sqlite.busy-timeout-ms` | 5000 | `sqliteBusyTimeoutMillis` |
| `sqlite.wal-enabled` | `false` | `sqliteWalEnabled` |

SQLite settings supplied explicitly in PostgreSQL mode are rejected.

## Leases, retries and recovery

Workers claim batches under a lease. Each claim gets a fresh token, and every write that completes the work checks the
token, so a worker whose lease was taken over cannot overwrite the new owner's result.

- **Expired leases count as attempts.** If a worker crashes or hangs past its lease, the claim is released and the
  attempt is counted. Inbox and outbox messages that exhaust `max-attempts` this way are dead-lettered with
  `Lease expired before processing completed`. Timers count the attempt but have no limit.
- **Failed batches do not strand their neighbours.** One failing item does not abandon the rest of its batch.
- **Obsolete timers are cancelled.** Leaving a step cancels its timers, including ones that are claimed or waiting to
  retry. A timer that fires after its workflow moved on is cancelled rather than retried.
- **Startup recovery** releases expired claims. Active workflows continue from the database; nothing needs to stay in
  memory between restarts.

Choose leases longer than the slowest expected handler or publication, and keep worker clocks synchronised.

### Dead letters

A message is dead-lettered when it runs out of attempts, whether its publication or processing kept failing or its
worker's lease kept expiring. Every dead letter is reported, so none goes unnoticed:

- **Lifecycle events.** `OUTBOX_DEAD_LETTERED` and `INBOX_DEAD_LETTERED` reach your `WorkflowLifecycleObserver` after
  the dead letter commits. Their attributes include `messageId`, the `destination` (outbox) or `sourceSystem` (inbox),
  and `failureCategory`: `publication_failed`, `inbox_processing_failed` or `lease_expired`.
- **Metrics.** `MetricsWorkflowLifecycleObserver` counts them as `jworkflow.outbox.dead_lettered` and
  `jworkflow.inbox.dead_lettered`.
- **Queries.** `engine.queries().deadLetteredOutbox(destination, afterMessageId, limit)` lists dead-lettered outbox
  messages for one destination (or all, with `null`), ordered by message id. Pass the last returned `messageId` to
  read the next page.
- **Recovery.** Fix the destination first, then call `JdbcOutboxApplication.republish(...)` (or
  `JdbcInboxApplication.reprocess(...)`). A republished message gets one more attempt; its attempt count is not
  reset.

### Paused workers

If a transaction's outcome is unknown (for example, the connection failed during commit) and the inbox or timer
worker cannot record that safely, the worker pauses instead of guessing. It logs an ERROR naming the worker and stops
polling; a manual `pollOnce()` on a paused inbox throws until the worker is recreated. Check the affected messages or timers, then recreate the inbox worker (for timers,
the engine) or restart the process.

### Logging

Workers log through `System.Logger` under `org.jworkflow.jdbc`; the in-memory engine uses `org.jworkflow.engine`.
Failures are logged with the exception type only, because messages can contain payloads or credentials.

## Schema and migrations

Choose exactly one migration owner per database or schema.

| Owner | SQLite | PostgreSQL | Runtime |
| --- | --- | --- | --- |
| Built-in | `.initialize(true)` | `.initialize(true)` | Applies and records versions with checksums. Needs DDL rights. |
| Flyway | `classpath:db/migration` | `classpath:db/postgresql/migration` | `.initialize(false)` |
| Liquibase | `db/changelog/db.changelog-master.xml` | `db/postgresql/changelog/db.changelog-master.xml` | `.initialize(false)` |

Do not point Flyway at `classpath:db`: the SQLite and PostgreSQL version numbers overlap. Histories written by
different owners are not interchangeable. Never edit an applied migration.

### Migration versions

| Version | Database | Change |
| --- | --- | --- |
| V1–V6 | SQLite | Baseline, durable messaging, timer history, routing indexes, redaction status, lease tokens |
| V7 | SQLite | Rewrites stored timestamps to a fixed nine-digit fraction so text order matches time order |
| V8 | SQLite | Index on `workflow_timer(workflow_instance_id)` |
| V1–V2 | PostgreSQL | Baseline and active-instance keyset index |
| V3 | PostgreSQL | Index on `workflow_timer(workflow_instance_id)` |

SQLite V7 rewrites every timestamp column and cannot be reversed; back up first and allow time on large databases.
See [upgrading](upgrading.md) for the full upgrade procedure.

### PostgreSQL accounts and connections

- Pre-create the database and a trusted schema, and set the same `currentSchema` for migration and runtime connections.
- The migration account needs schema ownership. The runtime account needs CONNECT, schema USAGE and
  SELECT/INSERT/UPDATE/DELETE on the application tables (including `workflow_event_sequence`, which is a table).
- The host owns the `DataSource`, pool sizing, TLS (`sslmode=verify-full` recommended), credentials and timeouts.
  Configure pgJDBC `connectTimeout`/`socketTimeout` and PostgreSQL `statement_timeout`/`lock_timeout`.
- Borrowed connections must be idle and in auto-commit mode. The adapter uses READ COMMITTED, restores connection
  state and never closes the host pool. Host/JTA/Spring transactions are not joined.

### SQLite notes

SQLite allows one writer at a time. Keep step handlers short: a durable command runs its handlers inside the write
transaction, so a slow handler delays every other writer until `sqlite.busy-timeout-ms` expires. WAL mode
(`sqliteWalEnabled(true)`) lets readers proceed while a write is in progress.
