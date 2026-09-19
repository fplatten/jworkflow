/**
 * Workflow commands, runtime configuration and execution contexts. Use {@link
 * org.jworkflow.engine.WorkflowEngine#builder()} to select in-memory or optional JDBC execution. JDBC
 * handlers can run inside database transactions, but external side effects are not transactional. Engines
 * own their internal workers and must be closed; supplied executors and DataSources remain host-owned.
 */
package org.jworkflow.engine;
