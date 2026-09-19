# Order fulfillment example

This module demonstrates how an application can use jworkflow without allowing workflow events to leak into
client or domain code.

## Package responsibilities

- `org.jworkflow.example.domain` contains the order domain model and has no jworkflow dependency.
- `org.jworkflow.example.application.command` contains the commands accepted by the application.
- `org.jworkflow.example.application.result` contains use-case results.
- `org.jworkflow.example.application.service` handles commands and implements application behavior without
  publishing workflow events.
- `org.jworkflow.example.application.port` defines the command-facing port used by clients.
- `org.jworkflow.example.infrastructure.workflow` is the anti-corruption boundary around jworkflow. Listeners
  translate workflow events into commands; the workflow command dispatcher translates application results back
  into workflow events.

The interaction is:

```text
client -> CreateOrderCommand -> application service -> order.created event
workflow event -> listener -> application command -> application service -> workflow event
```

`OrderFulfillmentExample` is the composition root. It is the only place that wires application and infrastructure
objects together.

## PostgreSQL restart demonstration

From the repository root, with JDK 17 or 21, Maven 3.9.6 and Docker running:

```powershell
./jworkflow-example/demo-postgresql.ps1
```

This Windows PowerShell example builds all modules and copies runtime dependencies using Maven dependency plugin 3.6.1. It starts an owned, disposable, digest-pinned PostgreSQL 17.11 container on a random loopback port, generates a password in the process environment, creates the `jworkflow` schema, and invokes each phase in a **separate JVM**. It removes only its own container in `finally` and restores the caller's environment. No password is committed or printed. Docker administrators can inspect container environments; use a host secret manager in deployments. The disposable database is removed after observation; this is a demonstration, not a persistent deployment. Use `-SkipBuild` only after a successful build.

Expected sequence:

1. `init` applies built-in migrations; rerunning `init` changes nothing.
2. `start` registers the order definition, starts `order-1001` at its approval wait (status RUNNING), and durably accepts an unprocessed approval inbox message.
3. `status` replays the original start key, prints the same instance/revision and RUNNING status.
4. `resume` reloads the persisted definition without registration, processes one inbox message and completes the order.
5. `status` replays the same original result identity and shows current COMPLETED state.
6. `publish` prints stable message IDs from the persisted outbox. The next `publish` prints count zero.
7. SQL shows one COMPLETED workflow, one PROCESSED inbox message and PUBLISHED outbox rows.

The publisher writes IDs to stdout; it is not a broker integration. Console output is not transactional: a crash after printing and before recording publication may print again. Use stable message identities to deduplicate real destinations. The demo uses one fixed order and consumes the dedicated schema's inbox/outbox; never point it at a shared application schema. `status` reconciles the original start command key before reading, so run `start` first.

For a pre-provisioned dedicated database/schema, supply credentials through your environment/secret provider:

```powershell
# Required: JWORKFLOW_JDBC_USERNAME and JWORKFLOW_JDBC_PASSWORD supplied by your host.
$env:JWORKFLOW_JDBC_URL = 'jdbc:postgresql://localhost:5432/orders?currentSchema=jworkflow&connectTimeout=10&socketTimeout=30&options=-c%20statement_timeout=10000%20-c%20lock_timeout=5000'
./jworkflow-example/run-postgresql.ps1 -Build -Phase init
./jworkflow-example/run-postgresql.ps1 -Phase start
./jworkflow-example/run-postgresql.ps1 -Phase status
./jworkflow-example/run-postgresql.ps1 -Phase resume
./jworkflow-example/run-postgresql.ps1 -Phase status
./jworkflow-example/run-postgresql.ps1 -Phase publish
./jworkflow-example/run-postgresql.ps1 -Phase publish
```

Use `init` only for the built-in migration owner with DDL credentials. For Flyway/Liquibase-owned schemas, build with the command below, omit `init`, then run phases with runtime DML-only credentials. See [configuration, exact resource paths, privileges and TLS](../README.md#durable-postgresql-engine). Never switch migration owners or run initialization with a restricted runtime account. Loopback Docker does not validate production TLS.

The underlying commands (no Maven Local install required):

```shell
mvn -B -ntp package org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies -DincludeScope=runtime -DexcludeArtifactIds=sqlite-jdbc -DoutputDirectory=target/dependency
# Windows classpath; use : separators on POSIX
java -cp "jworkflow-example/target/classes;jworkflow-jdbc/target/classes;jworkflow-jdbc/target/dependency/*" org.jworkflow.example.PostgresqlOrderExample start
```

The PowerShell wrapper explicitly excludes any stale `sqlite-jdbc` jar from the classpath. When using the manual wildcard command, use a fresh build directory. Runtime dependencies contain no migration tools or pool; all workflow and database credentials are configured at runtime.

Automated durable restart and three-way migration contracts:

```shell
mvn -B -ntp clean verify
mvn -B -ntp -Ppostgres-it clean verify
mvn -B -ntp -pl jworkflow-jdbc -am -Ppostgres-it -Dit.test=MigrationsPostgresIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

`DurableOrderPostgresIT` executes inbox/restart/outbox assertions against a real disposable server; `DurableOrderInboxRestartScenario` also runs on SQLite. Migration tests execute the built-in, Flyway 13.7.0 plus PostgreSQL module, and Liquibase 4.29.2 owners, compare application catalogs, rerun them, and exercise restricted runtime privileges. Database startup failures are failures, not skips. Main examples do not replace the full concurrency/recovery contracts.
