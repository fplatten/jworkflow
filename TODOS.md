# TODOS

## Outbox

### Per-destination outbox policies

**What:** Let `JdbcOutboxApplication` take per-destination lease, retry and batch settings, with claims filtered by destination.

**Why:** One lease and retry policy serves every destination today, so slow and fast destinations can't be tuned separately.

**Context:** `JdbcOutboxApplication` reads a single `outbox.lease-ms`, `outbox.max-attempts` and `outbox.batch-size` (default 32, published sequentially), and `claimEligibleFenced` has no destination filter. Fits the pre-0.1.0 API review.

**Effort:** M
**Priority:** P3
**Depends on:** None

## API

### Remove or wire up the in-process event bus

**What:** `EventBus`, `InMemoryEventBus`, `EventSubscriber`, `EventSubscription`, `EventListener`, `SubscribeTo` and related types (now in `org.jworkflow.internal.events`) aren't used by any engine, example or doc.

**Why:** Unused code still has to be maintained and covered by tests, and it suggests a feature that doesn't exist.

**Context:** Found during the 0.1.0 API review (`docs/api-review-0.1.0.md`, local). Decide whether it's a planned feature (then wire it in and document it) or dead code (then delete it). It's internal now, so either choice is non-breaking.

**Effort:** S
**Priority:** P3
**Depends on:** None

### Replace the reflective JDBC engine bridge

**What:** `WorkflowEngineBuilder` creates JDBC engines by calling the package-private `JdbcWorkflowEngine.create(...)` through `getDeclaredMethod` + `setAccessible`, so core needs no compile-time dependency on jworkflow-jdbc.

**Why:** It works on the classpath, which is what jworkflow supports today. But it would break under JPMS strong encapsulation, and the 17-parameter signature is matched by reflection only (a mismatch shows up at runtime, as an `IllegalStateException`).

**Context:** If `module-info.java` is ever added, replace it with an internal `ServiceLoader` SPI: an interface in `org.jworkflow.internal.engine`, implemented in jworkflow-jdbc. Covered today by `WorkflowEngineBuilderTest` and every JDBC contract test.

**Effort:** S
**Priority:** P3
**Depends on:** A decision to support the module path

## Docs

### Document the step execution model in the DSL reference

**What:** Add an "execution model" section to `docs/guide/dsl-reference.md`. A step runs when its triggering event arrives. A WAIT moves to its target and auto-advances only branch and gateway nodes. Use `step { on "event" }` rather than a WAIT followed by a STEP.

**Why:** It's easy to assume a STEP reached from a WAIT runs immediately; it doesn't, and the workflow silently stalls.

**Context:** The behavior lives in `InMemoryWorkflowEngine.advanceWaitNode` (around line 1037) and `acceptsEvent` (around line 1548). `jworkflow-example/src/main/groovy/workflows/order-fulfillment.groovy` shows the correct idiom.

**Effort:** S
**Priority:** P2
**Depends on:** None

## Completed

### Pre-release engine fixes

- **SQLite replay parity:** idempotent repeats return the command-time snapshot and event ids on SQLite too (`JdbcCommandReplayContractTest`).
- **Lease-expiry dead-letters observable:** lease sweeps report the rows they dead-letter (`UPDATE … RETURNING`) and emit `INBOX_DEAD_LETTERED` / the new `OUTBOX_DEAD_LETTERED` with `messageId` and `failureCategory`; failed publications emit `OUTBOX_DEAD_LETTERED` too (`JdbcDeadLetterEventContractTest`).
- **Dead-letter query:** `WorkflowQueryService.deadLetteredOutbox(destination, afterMessageId, limit)` with keyset paging (`JdbcDeadLetterQueryContractTest`).

All three run on SQLite and in the PostgreSQL contract suite.
**Completed:** 2026-10-04 (merged in PR #3)

### Engine timestamps follow the injected Clock

Command-result, workflow-definition and timer `created_at`/`updated_at` used the system clock. Only audit timestamps
were affected: due times and lease decisions already used the injected clock. Fixed in PR #1
with `JdbcClockContractTest` (2099 clock; runs on SQLite and in the PostgreSQL contract suite; fails without the fix)
and `NoWallClockGuardTest` (blocks new Java or SQL wall-clock reads in `jworkflow-jdbc`; schema initializers
allowlisted).
**Completed:** 2026-10-04 (merged in PR #1)
