/**
 * JDBC persistence and durable execution for SQLite and PostgreSQL.
 *
 * <p>A host DataSource takes precedence over a supplied Driver and URL credentials. The host owns pooling,
 * credentials, TLS and DataSource lifetime; the adapter closes borrowed connections and restores changed
 * connection state. Connections must initially be idle with auto-commit enabled. Unrelated host/JTA
 * transactions are not enlisted.</p>
 *
 * <p>PostgreSQL uses READ COMMITTED transactions, numeric(30,9) epoch-second relational timestamps,
 * text JSON/string identities, bytea payloads and bigint counters. SQLite retains its existing ISO-text
 * timestamps and serialized write behavior. Nested adapter work joins one connection with rollback-only
 * propagation; successful notifications run after outermost commit and connection cleanup.</p>
 *
 * <p>Select exactly one migration owner: built-in initialization, Flyway or Liquibase. PostgreSQL assets
 * reside under db/postgresql/migration and db/postgresql/changelog; they must not be mixed with SQLite
 * assets. Runtime-only connections need no DDL privileges when initialization is disabled. Configure
 * trusted schema selection on every host connection; this is not a tenant-isolation feature.</p>
 *
 * <p>Fenced timer/inbox/outbox claims use a new token for every acquisition. An expired but unreclaimed
 * token may finish; release or reclaim invalidates it. PostgreSQL rejects owner-only completion, while
 * SQLite retains the legacy compatibility APIs. Network publication remains at least once. Deadlocks
 * and ambiguous commits do not automatically replay handlers; reconcile using the original command key.</p>
 */
package org.jworkflow.jdbc;
