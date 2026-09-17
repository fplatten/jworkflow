# jworkflow

jworkflow is a lightweight, embeddable Java 17 workflow engine for command-driven, event-oriented applications. It supports an in-memory runtime for local execution and a durable SQLite/JDBC runtime with transactional state changes, restart recovery, timers, inbox/outbox processing, and optimistic concurrency control.

The project is framework-neutral: it does not require Spring, a broker, Flyway, or Liquibase at runtime.

## Supported workflow actions

- **Start** a workflow at a declared node or in response to a named event.
- **Step** through application actions or registered listener invocations.
- **Wait** for an external event, correlated to the correct workflow instance.
- **Retry** failed steps with bounded attempts and backoff.
- **Timeout** steps and waits, then emit an event or transition to another node.
- **Branch** using declarative variable comparisons or registered predicates.
- **Gateway** through exclusive or supported multi-route decisions.
- **Fork** work into parallel branches.
- **Join** after the required parallel branches complete.
- **Loop** through a bounded sequence while a declarative condition remains true.
- **Run a sub-workflow** with explicit version and input mappings.
- **Transition** on success or failure and optionally emit an event.
- **Complete** at a named terminal state.
- **Cancel, resume, or retry** a workflow through typed commands.

## Current MVP capabilities

- Command API for starting, signaling, retrying, canceling, and resuming workflow instances.
- Immutable workflow definitions authored with either a fluent Java builder or a restricted Groovy DSL.
- Steps, waits, retries, timeouts, branches, gateways, fork/join, bounded loops, sub-workflows, and terminal states.
- Event routing by exact workflow instance or an unambiguous workflow/correlation pair.
- Typed integration metadata through `IntegrationEvent`, `CorrelationId`, `CausationId`, and `TraceId`.
- Definition validation, checksums, immutable revisions, activation, and last-known-good replacement behavior.
- Durable SQLite execution using repository state as the authority rather than an in-memory mirror.
- Atomic snapshot, workflow-event, timer, command-result, and outbox persistence.
- Deterministic JSON persistence, optimistic locking, and recovery after process restart.
- Durable timers and event waits suitable for workflows that remain active for days or longer.
- Inbox deduplication, retries, manual reprocessing, claim recovery, and dead-letter status.
- Transactional outbox publication with retries, manual republishing, claim recovery, and at-least-once delivery semantics.
- CQRS queries for workflow details, timelines, failures, stuck workflows, waits, timers, and pending outbox messages.
- Lifecycle observers, basic metrics/logging adapters, and trace-context propagation points.
- Central event-capture policies for metadata filtering and payload redaction before persistence or publication.

## Modules

| Module | Purpose |
|---|---|
| `jworkflow-core` | Domain models, commands, definition APIs, runtime, ports, query API, inbox/outbox services, and in-memory engine. |
| `jworkflow-jdbc` | SQLite/JDBC repositories, transactions, durable engine, recovery, JSON codec, and schema resources. |
| `jworkflow-example` | Order-fulfillment examples and integration scenarios, including parallel routing and restart recovery. |

Only SQLite is an MVP JDBC engine type. PostgreSQL, MySQL, H2, and other dialects remain backlog items.

## Build and test

Requirements:

- JDK 17 or newer
- Maven 3.8 or newer

Run the complete reactor:

```shell
mvn clean test
```

The JUnit suites execute the core, file-backed SQLite, schema migration, recovery, inbox/outbox, security, and example contracts.

Install the current snapshots into Maven Local for use by another local project:

```shell
mvn clean install
```

## Dependencies

For in-memory execution, add the core module:

```xml
<dependency>
    <groupId>org.jworkflow</groupId>
    <artifactId>jworkflow-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For durable SQLite execution, add the JDBC adapter and a SQLite driver:

```xml
<dependency>
    <groupId>org.jworkflow</groupId>
    <artifactId>jworkflow-jdbc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.46.1.0</version>
</dependency>
```

These snapshot coordinates must first be built locally; no public release is implied by this repository state.

## Define workflows

The fluent Java builder produces the same immutable model used by the Groovy compiler:

```java
WorkflowDefinitionBuilder definition = WorkflowDefinitionBuilder
        .workflow("employee-onboarding")
        .version("1.0.0")
        .startAt("collectTaxForm")
        .step("collectTaxForm", step -> step
                .action("tax-form.request")
                .onSuccess("waitingForTaxForm"))
        .waitFor("waitingForTaxForm", wait -> wait
                .event("employee.tax-form.completed")
                .correlateBy("employeeId")
                .then("completed"))
        .end("completed");
```

Groovy sources are declarative and compiled through a deny-by-default AST validator. Accepted source is interpreted into immutable definition and listener-invocation metadata; arbitrary user Groovy is not retained or executed.

```groovy
workflow("employee-onboarding") {
    version "1.0.0"
    correlateBy "employeeId"
    start when: "employee.onboarding.started"

    step("requestTaxForm") {
        on "employee.onboarding.started"
        run { event, context ->
            context.listener("taxFormListener").request(event)
        }
        then waitFor "employee.tax-form.completed", goTo: "completed"
    }

    end("completed")
}
```

Load definitions and register infrastructure listeners through the builder:

```java
WorkflowEngine engine = WorkflowEngine.builder()
        .definitions(new ClasspathWorkflowDefinitionSource(
                List.of("workflows/employee-onboarding.groovy")))
        .listener("taxFormListener", taxFormListener)
        .build();
```

Definitions are validated before activation. Invalid replacements preserve the last-known-good definition. Listener objects remain infrastructure boundaries; the DSL can only refer to registered listener IDs and approved invocation syntax.

## Use the command API

Application code should submit command objects and receive typed results:

```java
try (WorkflowEngine engine = WorkflowEngine.builder()
        .definition(definition)
        .stepHandler("tax-form.request", context -> StepResult.success())
        .build()) {

    StartWorkflowResult started = engine.start(new StartWorkflowCommand(
            "employee-onboarding",
            null,
            "employee-123",
            Map.of("employeeId", "employee-123"),
            WorkflowCommandMetadata.defaults(
                    "employee-onboarding", null, null, "employee-123")));

    engine.signal(new SignalWorkflowCommand(
            started.workflowInstanceId(),
            new WorkflowSignal(
                    "employee.tax-form.completed",
                    "employee-123",
                    null,
                    "employee-123",
                    Instant.now(),
                    Map.of("employeeId", "employee-123")),
            WorkflowCommandMetadata.defaults(
                    "employee-onboarding", null,
                    started.workflowInstanceId(), "employee-123")));
}
```

Convenience overloads such as `start(workflowKey, businessKey, variables)` remain available, but commands are the primary application boundary. Idempotency metadata can be supplied so repeated commands return the original result while conflicting reuse is rejected.

## Durable SQLite engine

PostgreSQL and MySQL support is planned for the next release.

Use the explicit `SQLITE` engine type. It can use a JDBC URL with an optional host-provided `Driver`, or a host-managed `DataSource`.

```java
try (WorkflowEngine engine = WorkflowEngine.builder()
        .type(WorkflowEngine.Type.SQLITE)
        .jdbcUrl("jdbc:sqlite:var/jworkflow.sqlite")
        .initialize()
        .definitions(new ClasspathWorkflowDefinitionSource(
                List.of("workflows/employee-onboarding.groovy")))
        .sqliteBusyTimeoutMillis(5_000)
        .timerPollIntervalMillis(1_000)
        .recoveryLeaseMillis(30_000)
        .timerBatchSize(100)
        .build()) {
    // Submit commands or route integration events.
}
```

With a `DataSource`:

```java
WorkflowEngine engine = WorkflowEngine.builder()
        .type(WorkflowEngine.Type.SQLITE)
        .dataSource(dataSource)
        .build();
```

`.initialize()` applies bundled versioned schema resources and records their versions. It is convenient for development and embedded deployments. For production, apply schema changes during deployment and omit `.initialize()` so the runtime database account does not need DDL permissions.

The same V1–V5 SQL resources drive the built-in initializer, Flyway assets, and Liquibase changelog:

- `jworkflow-jdbc/src/main/resources/db/migration/`
- `jworkflow-jdbc/src/main/resources/db/changelog/db.changelog-master.xml`

Flyway and Liquibase are test/deployment choices, not mandatory runtime dependencies.

### Durability model

- Each durable command loads its exact workflow definition revision and current snapshot from JDBC.
- Snapshot updates use `lock_version` optimistic locking.
- State, events, timer operations, command results, and outbox inserts share one JDBC transaction.
- Definitions, variables, pending waits, and timers are reconstructed after restart.
- JSON persistence supports nested maps and lists, strings, integral and decimal numbers, booleans, nulls, empty collections, and Java time values.
- Legacy non-empty values previously stored using `Map.toString()` cannot be reconstructed losslessly.
- SQLite claims use short transactions and leases; external publication does not occur while holding a database write transaction.

Long-running workflows do not need to remain in memory. An instance waiting for an event or timer can be inactive for days, survive engine shutdown, and resume when a correctly routed event or due timer is processed.

## Inbox and transactional outbox

Infrastructure adapters can first accept external events into the inbox, deduplicated by `(sourceSystem, externalEventId)`, then translate them into core commands. Successful processing updates the workflow and inbox status atomically when they share the database.

Outbound workflow events are inserted into the outbox in the workflow transaction. A publisher claims and publishes them afterward through a configured `DestinationPublisher`. Failed attempts are append-only, retryable, and eventually dead-lettered according to policy. Manual reprocessing and republishing preserve the original message and its metadata.

Outbox delivery is **at least once**, not exactly once. A crash after destination acceptance but before database confirmation can cause redelivery; consumers must deduplicate using the stable idempotency key.

## Queries

Persistence-backed engines expose a read-only query service:

```java
WorkflowQueryService queries = engine.queries();

Optional<WorkflowDetail> workflow =
        queries.findByBusinessKey("employee-onboarding", "employee-123");
WorkflowTimeline timeline =
        queries.timeline(started.workflowInstanceId());
List<WorkflowInstanceSummary> failures = queries.failed(100);
List<PendingTimerView> timers = queries.pendingTimers(100);
List<OutboxStateView> pendingPublications = queries.pendingOutbox(100);
```

Custom projections may replay a workflow's ordered event history without mutating durable workflow state.

## Event capture and redaction

Capture-all remains the compatibility default. Configure a policy to prevent sensitive fields from entering workflow history, snapshots derived from events, inbox/outbox records, lifecycle observations, or query timelines:

```java
WorkflowEngine engine = WorkflowEngine.builder()
        .eventCapturePolicy(new FilteringEventCapturePolicy(
                Set.of("authorization", "cookie"),
                Set.of("secret"),
                true))
        .build();
```

`MetadataOnlyEventPolicy.INSTANCE` retains routing/schema metadata while removing payload and message attributes. Applications may implement `EventCapturePolicy` for their own filtering rules.

## Observability

Register a `WorkflowLifecycleObserver` through `lifecycleObserver(...)` to receive isolated lifecycle notifications. Core includes minimal logging and metrics observers; applications can adapt this boundary to their telemetry system. Observer failures do not control workflow state, and durable notifications occur only after commit.

## Configuration properties

Basic builder configuration can also be supplied with `Properties`:

```properties
jworkflow.engine.type=sqlite
jworkflow.jdbc.url=jdbc:sqlite:var/jworkflow.sqlite
jworkflow.schema.initialize=false
jworkflow.sqlite.busy-timeout-ms=5000
jworkflow.sqlite.wal-enabled=false
```

Advanced settings can be passed as `jworkflow.setting.<name>`. Prefer typed builder methods for polling intervals, claim leases, batch sizes, retry attempts, and backoff because they validate supported bounds.

## Examples

- [Order fulfillment example](jworkflow-example/src/main/java/org/jworkflow/example/OrderFulfillmentExample.java)
- [Groovy order workflow](jworkflow-example/src/main/groovy/workflows/order-fulfillment.groovy)
- [Parallel-order routing test](jworkflow-example/src/test/java/org/jworkflow/example/ParallelOrderRoutingExampleTest.java)
- [Durable restart example](jworkflow-example/src/test/java/org/jworkflow/example/DurableOrderRestartExampleTest.java)

Run the order example after compiling the reactor:

```shell
mvn -pl jworkflow-example -am test-compile
mvn -pl jworkflow-example -Dexec.mainClass=org.jworkflow.example.OrderFulfillmentExample exec:java
```

## Delivery guarantees and boundaries

- Workflow state is deterministic from its immutable definition revision, persisted snapshot, and command/event input.
- Workflow event history and processing/publication attempts are append-only.
- Same-instance concurrent JDBC writers are resolved with optimistic locking rather than JVM-wide synchronization.
- Inbox deduplication and command idempotency protect repeated inputs within their documented identities.
- External publication is at least once and requires consumer-side idempotency.
- `SecureASTCustomizer` and the custom AST validator constrain the Groovy DSL, but they are not an operating-system sandbox.
- The host application owns authentication, authorization, secret management, database backups, and transport security.

## License

Apache License 2.0.
