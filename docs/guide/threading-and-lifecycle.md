# Threading and lifecycle

## Thread safety

Every engine is safe to share between threads.

- The in-memory engine serialises work on one workflow instance with a per-instance lock. Different instances run in
  parallel.
- The durable engines run each command in its own database transaction and protect each instance with optimistic
  locking. When two writers race on the same instance, one of them fails with `WorkflowOptimisticLockException`; retry
  the command with the same idempotency key.

## Which thread runs your code

| Work | In-memory engine | SQLite / PostgreSQL engine |
| --- | --- | --- |
| Commands (`start`, `signal`, `retryFailedStep`, `cancel`, `resume`) and their step handlers | Caller's thread | Caller's thread, inside the command's transaction |
| Events passed to `engine.publish(...)` | Event-loop thread `jworkflow-events` | Caller's thread (routed immediately) |
| Timeouts and step retries | Event-loop thread `jworkflow-events` | Timer worker `jworkflow-jdbc-timers-*` |
| Inbox translation and the resulting commands | — | Inbox worker `jworkflow-jdbc-inbox-*`, or the thread calling `pollOnce()` |
| `DestinationPublisher.publish(...)` | — | Outbox worker `jworkflow-jdbc-outbox-*`, or the thread calling `pollOnce()` |
| Lifecycle observers and after-commit notifications | Thread that made the change | Thread that committed, after the outermost commit |

Step handlers and listeners can therefore run on several threads at once, for different workflow instances. Keep them
thread-safe and short; on SQLite a long handler holds the database write lock for its whole duration.

## Threads the engine starts

| Thread | Starts | Stops |
| --- | --- | --- |
| `jworkflow-events` (in-memory) | On the first published event or scheduled timer | After about one idle second with no pending timers, or on `close()` |
| `jworkflow-jdbc-timers-*` | In `build()`, unless `timerPolling(false)` | `engine.close()`, which waits up to 5 seconds |
| `jworkflow-jdbc-inbox-*` | `inbox.start()` | `inbox.close()`, which waits up to 5 seconds |
| `jworkflow-jdbc-outbox-*` | `outbox.start()` | `outbox.close()`, which waits up to 5 seconds |

All of them are daemon threads, so they do not keep the JVM alive. A worker that is still running when the JVM exits
is interrupted mid-batch; its claims expire and are retried, counting as an attempt. Close engines and workers on
shutdown.

## Closing

`WorkflowEngine` and the inbox/outbox workers are `AutoCloseable`.

```java
try (JdbcWorkflowEngine engine = (JdbcWorkflowEngine) builder.build();
     JdbcOutboxApplication outbox = engine.outbox(publishers)) {
    outbox.start();
    // ...
} // closes the outbox first, then the engine
```

- Close inbox and outbox workers before the engine; closing the engine does not stop them.
- Do not use an engine after `close()`. A closed in-memory engine throws on `publish(...)`; a closed durable engine
  refuses to create inbox or outbox workers, but it does not guard every command, so stop submitting work first.
- The engine never closes a host-supplied `DataSource`.

## The process-wide engine

Applications that cannot pass the engine around can install one process-wide instance:

```java
WorkflowEngine engine = WorkflowEngine.builder().definition(definition).buildAndSetInstance();
// elsewhere
WorkflowEngine.instance().start("order", "order-1", Map.of());
```

- `WorkflowEngine.instance()` throws if no engine is installed.
- `setInstance(...)` (and `buildAndSetInstance()`) throw if one is already installed. Use
  `WorkflowEngine.replaceInstance(...)` to swap deliberately.
- The holder does not own the engine. `WorkflowEngine.clearInstance()` does not close it: close the engine yourself,
  then clear the reference.

Prefer passing the engine explicitly (for example through dependency injection); the process-wide instance is a
convenience for small applications and scripts.
