# jworkflow

jworkflow is a lightweight, embeddable Java 17 workflow engine for command-driven, event-oriented applications. It supports an in-memory runtime for local execution and durable SQLite and PostgreSQL JDBC runtimes with transactional state changes, restart recovery, timers, inbox/outbox processing, and optimistic concurrency control.

The project is framework-neutral: it does not require Spring, a broker, Flyway, or Liquibase at runtime.

## Project status

jworkflow is at MVP status. PostgreSQL implementation and acceptance gates are complete for fresh deployments on the tested server/JDK matrix described below. Local verification includes ordinary SQLite/in-memory regressions, real PostgreSQL concurrency and migration contracts, and a separate-JVM restart example. Hosted CI results are reported separately from these local runs.

The project remains `0.1.0-SNAPSHOT`. Maven Central release preparation still includes public API Javadoc completion, release-version/API review, signing, and release-bundle validation. Generated source and Javadoc JARs do not imply that a release has been published.

For a runnable PostgreSQL walkthrough, see the [environment-configured restart demonstration](jworkflow-example/README.md#postgresql-restart-demonstration).

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
- Durable SQLite and PostgreSQL execution using repository state as the authority rather than an in-memory mirror.
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
| `jworkflow-jdbc` | SQLite/PostgreSQL JDBC repositories, transactions, durable engine, recovery, JSON codec, and schema resources. |
| `jworkflow-example` | Order-fulfillment examples and integration scenarios, including parallel routing and restart recovery. |

Durable engine types are `SQLITE` and `POSTGRESQL`. PostgreSQL support targets fresh deployments. MySQL, H2, SQLite-to-PostgreSQL transfer, and native JSONB/UUID conversion remain backlog work.

## Build and test

Requirements:

- JDK 17 or newer
- Maven 3.8 or newer

Run the complete reactor:

```shell
mvn clean test
```

The JUnit suites execute the core, file-backed SQLite, schema migration, recovery, inbox/outbox, security, and example contracts.

Build the library, source, and Javadoc JARs for all three modules:

```shell
mvn package
```

Each module's `target/` directory contains its main JAR, `-sources.jar`, and `-javadoc.jar`. The source and Javadoc artifacts are also attached automatically during `install` and `deploy`.

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

The same V1–V6 SQL resources drive the built-in initializer, Flyway assets, and Liquibase changelog:

- `jworkflow-jdbc/src/main/resources/db/migration/`
- `jworkflow-jdbc/src/main/resources/db/changelog/db.changelog-master.xml`

Flyway and Liquibase are test/deployment choices, not mandatory runtime dependencies.

### Durability model

- Each durable command loads its exact workflow definition revision and current snapshot from JDBC.
- Snapshot updates use `lock_version` optimistic locking.
- State, events, timer operations, command results, and outbox inserts share one JDBC transaction.
- Definitions, variables, pending waits, and timers are reconstructed after restart.
- Workflow variables support nested maps and lists, strings, integral and decimal numbers, booleans, nulls, and empty collections. Convert domain-specific Java time values in variable maps to strings; typed time metadata is encoded as ISO-8601 text.
- Legacy non-empty values previously stored using `Map.toString()` cannot be reconstructed losslessly.
- SQLite claims use short transactions and leases; external publication does not occur while holding a database write transaction.

Long-running workflows do not need to remain in memory. An instance waiting for an event or timer can be inactive for days, survive engine shutdown, and resume when a correctly routed event or due timer is processed.

## Durable PostgreSQL engine

The locally executed matrix is PostgreSQL **16.15, 17.11 and 18.6**, each on Oracle JDK **17.0.10 and 21.0.7**, with **pgJDBC 42.7.13** and Maven **3.9.6**. Each combination passed 61 ordinary tests and 213 PostgreSQL integration tests. Java bytecode remains release 17. The pinned Ubuntu/Temurin CI matrix is configured but hosted jobs have not been executed; this is local validation, not a claim about every driver, patch version, or deployment.

Add `jworkflow-jdbc` as above and explicitly add your driver (both JDBC drivers are optional dependencies):

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.13</version>
</dependency>
```

No SQLite, pool, Spring, broker, Flyway or Liquibase dependency is required. A forked consumer tests operation without SQLite or migration tooling; the runnable example below also excludes SQLite.

```java
try (WorkflowEngine engine = WorkflowEngine.builder()
        .type(WorkflowEngine.Type.POSTGRESQL)
        .jdbcUrl(System.getenv("JWORKFLOW_JDBC_URL"))
        .username(System.getenv("JWORKFLOW_JDBC_USERNAME"))
        .password(System.getenv("JWORKFLOW_JDBC_PASSWORD"))
        .initialize(false) // deployment has already migrated the schema
        .setting("postgres.command-lock-timeout-ms", "5000")
        .build()) {
    // Register definitions/handlers and submit commands.
}
```

To supply a Driver, add `.driver(new org.postgresql.Driver())` to the URL builder. For a host-managed `javax.sql.DataSource`, use `.type(POSTGRESQL).dataSource(dataSource).initialize(false)` (qualify POSTGRESQL with `WorkflowEngine.Type`). DataSource takes precedence over Driver/URL/credentials and uses its own `getConnection()` configuration. Supplied Driver comes next; otherwise the adapter loads the selected backend driver. Actual database metadata must match the selected type. Explicit SQLite busy-timeout/WAL settings are rejected in PostgreSQL mode.

Properties use `jworkflow.engine.type=postgresql`, `jworkflow.jdbc.url`, `jworkflow.jdbc.username`, `jworkflow.jdbc.password`, `jworkflow.schema.initialize=false` and `jworkflow.setting.postgres.command-lock-timeout-ms=5000`. Populate credentials from the host's secret provider; the builder does not expand `${ENV}` text. Driver and DataSource are programmatic objects.

### Schema and migration ownership

Pre-create a database and trusted application schema. Set `currentSchema=jworkflow` in pgJDBC's URL or DataSource consistently for migration and runtime. Do not switch schemas on borrowed pooled sessions. Select exactly one migration owner per schema:

| Owner | PostgreSQL resources/configuration | Runtime |
| --- | --- | --- |
| Built-in | `.initialize(true)` applies `db/postgresql/migration/V1__postgresql_baseline.sql` and `V2__active_instance_keyset_index.sql`, atomically under a schema-scoped lock, with checksum history | May rerun with its owning account; normal runtime can disable initialization |
| Flyway | `locations("classpath:db/postgresql/migration")`, matching `defaultSchema`/`schemas`, `createSchemas(false)` | `.initialize(false)` |
| Liquibase | `db/postgresql/changelog/db.changelog-master.xml`, matching default/history schema | `.initialize(false)` |

Flyway core **13.7.0** needs matching `flyway-database-postgresql`; Liquibase **4.29.2** was tested. These are optional deployment tools and test dependencies, not production requirements. Do not scan `classpath:db`: SQLite's separate `db/migration` versions overlap. Histories cannot be silently adopted or interchanged. Do not edit applied migrations. V2 is an additive normal index build; schedule DDL before workers or in a maintenance window. SQLite V1–V5 remain unchanged; V6 adds lease tokens. Stop old workers before upgrade; mixed-version worker rollout is unsupported.

The migration account needs schema ownership/DDL rights. The runtime account needs database CONNECT, schema USAGE and SELECT/INSERT/UPDATE/DELETE on the 13 application tables, including `workflow_event_sequence`; it needs neither schema CREATE nor migration-history access when initialization is false. Grant application tables explicitly, excluding owner histories. The sequence allocator is a table, not a PostgreSQL sequence.

### Connections, retries and delivery

The host owns DataSource lifecycle, pool sizing, TLS, database credentials and connect/socket/statement/lock timeouts. The adapter closes borrowed connections, restores changed state and never closes the host pool. Borrowed connections must be idle and auto-commit enabled. Adapter transactions use READ COMMITTED and nested calls join the same adapter connection; unrelated host/JTA/Spring transactions are not enlisted. HikariCP 7.1.0 was tested, but is not required.

Configure bounded pgJDBC `connectTimeout`/`socketTimeout` (seconds) and PostgreSQL `statement_timeout`/`lock_timeout` (milliseconds, via properly URL-encoded `options` or host configuration). TLS deployments should use `sslmode=verify-full` with a trusted CA and matching hostname; the loopback disposable example does not test TLS. See [pgJDBC TLS](https://jdbc.postgresql.org/documentation/ssl/) and [connection properties](https://jdbc.postgresql.org/documentation/use/).

Complete command idempotency keys are protected through the outer commit; conflicting type/content is rejected. Preserve the original key when reconciling uncertain commit outcomes. Distinct-key writers retain optimistic locking. Deadlocks, timeouts and lost acknowledgments do not automatically replay handlers. Inbox/timer workers quarantine unsafe replay outcomes; failed quarantine pauses that local worker and requires operator reconciliation. Such a pause is process-local. External handler effects may repeat after crash or caller-driven retry.

Claims use bounded ordered `FOR UPDATE SKIP LOCKED` transactions. Every acquisition gets a new opaque token, even for the same worker ID. Built-in workers fence dependent workflow/attempt/status writes atomically. An expired token may finish until release/reclaim invalidates it. PostgreSQL rejects owner-only finalization; custom workers must use token-aware APIs. Legacy SQLite owner-only APIs remain weaker; old constructors remain available, but Java record component/equality/serialization shapes now include the token. Core custom repositories must implement fencing rather than silently fall back. There is no separate in-memory durable inbox/outbox repository.

Synchronize worker clocks; choose leases longer than expected work and pauses. Poll/batch/lease/retry/backoff builder settings remain host-tunable. Outbox publication occurs outside database transactions and is **at least once**: a send followed by crash or failed recording may send again. Deduplicate at the destination. In-process after-commit observers are best effort, not durable delivery.

### Storage, queries and boundaries

PostgreSQL stores JSON as versioned text, string IDs, binary bodies as `bytea`, long counters as `bigint`, and all 28 application relational Instants as exact `numeric(30,9)` epoch seconds. JSON-embedded times remain ISO strings. Negative epochs and nanoseconds round-trip through BigDecimal. Do not convert authoritative values through double or PostgreSQL timestamp. Example diagnostics (numeric is authoritative; timestamp display is approximate):

```sql
SELECT id, updated_at, floor(updated_at) AS epoch_second,
       (updated_at-floor(updated_at))*1000000000 AS nanos,
       to_timestamp(updated_at::double precision) AT TIME ZONE 'UTC' AS approximate_utc
FROM jworkflow.workflow_instance ORDER BY updated_at,id LIMIT 20;
SELECT id, COALESCE(next_attempt_at,due_at) AS eligible_at
FROM jworkflow.workflow_timer
WHERE status_value IN ('PENDING','RETRY_SCHEDULED')
  AND COALESCE(next_attempt_at,due_at) <= extract(epoch FROM statement_timestamp())::numeric(30,9)
ORDER BY COALESCE(next_attempt_at,due_at),created_at,id LIMIT 20;
```

PostgreSQL text rejects raw NUL and enforces varchar bounds. Relational idempotency keys escape percent/NUL; the 255-character limit applies after encoding. JSON payload encoding is unchanged. Exact persisted definition revisions survive newer same-name/version registration and restart. Automatic durable event starts and tenant partitioning are not added: tenant-scoped routes are rejected. Lazy startup and active/routing pages and claim/recovery batches are bounded, but deep-page scan work grows with data. Full timelines load an instance's entire history; the older time-only event cursor can omit timestamp ties. SQLite's existing variable-width ISO fractional-time ordering limitation remains characterized, not fixed.

The performance validation used 20,000 snapshots, 100,000 events and 30,000 rows per queue on a 2-CPU/1-GiB PostgreSQL container with four workers. All declared ceilings passed; this is not a throughput/SLA or arbitrary-size memory guarantee. Large definition histories, cold storage, failover/network partitions, TLS rotation and production capacity remain deployment validation work. SQLite transfer and native JSONB/UUID conversion are outside this fresh-deployment release scope.

### Run PostgreSQL validation and the example

With a Docker-compatible runtime available:

```shell
mvn -B -ntp clean verify
mvn -B -ntp -Ppostgres-it clean verify
```

The profile runs ordinary Surefire tests plus real `*PostgresIT` tests through Failsafe. Database unavailability fails the selected profile. The default image is digest-pinned PostgreSQL 17.11; `-Dpostgres.image=...` can select another image. Testcontainers 2.0.5 and migration tooling are test-only. XML reports are in each module's `target/surefire-reports` and `target/failsafe-reports`; operational EXPLAIN/results are in JDBC `target/pg12-evidence`.

See the [runnable PostgreSQL example](jworkflow-example/README.md#postgresql-restart-demonstration) to build, migrate, start, restart through the inbox, publish and inspect persisted state. It uses environment credentials and separate JVMs without SQLite. No artifact publication or release is implied.

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

Register a `WorkflowLifecycleObserver` through `lifecycleObserver(...)` to receive isolated lifecycle notifications. Core includes minimal logging and metrics observers; applications can adapt this boundary to their telemetry system. Observer failures do not control workflow state. Built-in JDBC notifications wait for the outermost successful adapter commit and connection cleanup; rollback or failed commit discards them. Delivery is best effort and observer failures cannot undo committed work. Custom transaction managers must implement synchronization to provide that timing. Use the transactional outbox for durable external delivery.

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
mvn -B -ntp package org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies -DincludeScope=runtime -DexcludeArtifactIds=sqlite-jdbc -DoutputDirectory=target/dependency
# Windows; on POSIX use : instead of ; in the classpath
java -cp "jworkflow-example/target/classes;jworkflow-jdbc/target/classes;jworkflow-jdbc/target/dependency/*" org.jworkflow.example.OrderFulfillmentExample
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
