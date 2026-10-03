# TODOS

## Outbox

### Make lease-expiry dead-letters observable

**What:** When an expired outbox lease exhausts its attempts, `JdbcLeaseSupport.release` (around line 151) sets `DEAD_LETTER` without emitting any lifecycle event. Failed publications do emit `OUTBOX_PUBLICATION_FAILED`, but with null message and instance ids.

**Why:** Operators and observers can't tell a message was dead-lettered by lease expiry, which is a silent failure in production.

**Context:** Options: emit an outbox dead-letter lifecycle event from both paths (with message id and destination), or document that a periodic query is needed. Note that expired leases count as attempts, so a delivery recovered after a crash records `attempt_number = 2`; `republish` keeps `attempt_count`, so each republish allows one more attempt.

**Effort:** S
**Priority:** P2
**Depends on:** None

### Filtered, pageable dead-letter query

**What:** Add a query for dead-lettered outbox messages by destination with keyset paging (for example `findDeadLettered(destination, afterId, limit)` on `OutboxRepository` plus the JDBC implementation and the query service).

**Why:** `findPending(limit)` (`JdbcOutboxRepository.java:186`) mixes PENDING/CLAIMED/RETRY_SCHEDULED/DEAD_LETTER across all destinations, applies the limit first and can't page, so dead-letters can be hidden behind older rows.

**Context:** `idx_workflow_outbox_status` can back the status filter. Add a contract test with more rows than one page, with mixed statuses and destinations, on SQLite and PostgreSQL.

**Effort:** S
**Priority:** P2
**Depends on:** None

### Per-destination outbox policies

**What:** Let `JdbcOutboxApplication` take per-destination lease, retry and batch settings, with claims filtered by destination.

**Why:** One lease and retry policy serves every destination today, so slow and fast destinations can't be tuned separately.

**Context:** `JdbcOutboxApplication` reads a single `outbox.lease-ms`, `outbox.max-attempts` and `outbox.batch-size` (default 32, published sequentially), and `claimEligibleFenced` has no destination filter. Fits the pre-0.1.0 API review.

**Effort:** M
**Priority:** P3
**Depends on:** None

## Persistence

### SQLite command replay parity with PostgreSQL

**What:** Store the command snapshot for SQLite too, so an idempotent repeat returns the original state on both backends.

**Why:** A repeated command returns the original snapshot on PostgreSQL but the current snapshot on SQLite, so replay semantics differ by backend without warning.

**Context:** In `JdbcWorkflowEngine` (around line 341), the snapshot and emitted ids are written into the command result only when `type()==POSTGRESQL`. Check `docs/guide/persisted-data-format.md` for compatibility with existing rows.

**Effort:** M
**Priority:** P2
**Depends on:** None

## Docs

### Document the step execution model in the DSL reference

**What:** Add an "execution model" section to `docs/guide/dsl-reference.md`. A step runs when its triggering event arrives. A WAIT moves to its target and auto-advances only branch and gateway nodes. Use `step { on "event" }` rather than a WAIT followed by a STEP.

**Why:** It's easy to assume a STEP reached from a WAIT runs immediately; it doesn't, and the workflow silently stalls.

**Context:** The behavior lives in `InMemoryWorkflowEngine.advanceWaitNode` (around line 1037) and `acceptsEvent` (around line 1548). `jworkflow-example/src/main/groovy/workflows/order-fulfillment.groovy` shows the correct idiom.

**Effort:** S
**Priority:** P2
**Depends on:** None

## Completed

### Engine timestamps follow the injected Clock

Command-result, workflow-definition and timer `created_at`/`updated_at` used the system clock. Only audit timestamps
were affected: due times and lease decisions already used the injected clock. Fixed on branch `fix/clock-injection`
with `JdbcClockContractTest` (2099 clock; runs on SQLite and in the PostgreSQL contract suite; fails without the fix)
and `NoWallClockGuardTest` (blocks new Java or SQL wall-clock reads in `jworkflow-jdbc`; schema initializers
allowlisted).
**Completed:** 2026-10-03 (uncommitted)
