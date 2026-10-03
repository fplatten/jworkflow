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

## API changes

- `WorkflowMutation` has a new `createdInstances` component. The previous five-argument constructor remains.
- `InboxMessage.asReceived()` and `InboxProcessingService.partiallyRouted(...)` are new.
- `JdbcJsonCodec.TYPED_NUMBERS_VERSION` is new.
