package org.jworkflow.engine;

import org.jworkflow.events.*;
import org.jworkflow.model.*;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Main command API for starting and advancing workflow instances.
 *
 * <p>Use command metadata for stable idempotency identities. JDBC engines persist snapshots, events, timers,
 * results and outbox intent atomically; they do not make external handler effects transactional. Close engines
 * when finished. Host DataSources remain host-owned. Explicit routing and persistence queries are optional
 * capabilities; the default implementations reject them.</p>
 */
public interface WorkflowEngine extends EventPublisher, org.jworkflow.routing.WorkflowEventRouter, AutoCloseable {
    /**
     * Runtime backend selected by the builder. JDBC backends require the adapter module and the corresponding
     * optional driver or a working host DataSource.
     */
    enum Type {
        /**
         * Process-local execution without durable restart recovery.
         */
        IN_MEMORY,
        /**
         * Durable execution using the optional SQLite JDBC adapter.
         */
        SQLITE,
        /**
         * Durable execution using the optional PostgreSQL JDBC adapter.
         */
        POSTGRESQL
    }

    /**
     * Creates a mutable engine builder using the in-memory backend by default.
     * @return this builder for further configuration
     */
    static WorkflowEngineBuilder builder() {
        return new WorkflowEngineBuilder();
    }

    /**
     * Builds an engine from backend/connection properties using the reflective JDBC adapter when selected.
     * @param properties engine configuration to apply
     * @return the configured engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    static WorkflowEngine create(WorkflowEngineProperties properties) throws ClassNotFoundException {
        return builder().properties(properties).build();
    }

    /**
     * Returns the process-wide installed engine, failing if none has been installed.
     * @return the process-wide installed engine, failing if none has been installed
     */
    static WorkflowEngine instance() {
        return WorkflowEngines.instance();
    }

    /**
     * Installs the process-wide engine reference without assuming ownership of its lifetime.
     * @param engine engine whose lifetime remains the caller's responsibility
     */
    static void setInstance(WorkflowEngine engine) {
        WorkflowEngines.setInstance(engine);
    }

    /**
     * Clears the process-wide reference without closing the previously installed engine.
     */
    static void clearInstance() {
        WorkflowEngines.clearInstance();
    }

    /**
     * Registers a listener under the supplied ID. The compatibility default rejects registration for engines that
     * do not implement it.
     * @param listenerId registered infrastructure listener identity
     * @param listener host listener object to register or invoke
     */
    default void registerListener(String listenerId, Object listener) {
        throw new UnsupportedOperationException("Listener registration is not supported by this workflow engine");
    }

    /**
     * Wraps submitted work with the submitting thread's workflow context. The delegate executor remains
     * caller-owned.
     * @param delegate host executor or observer being adapted; ownership stays with the caller
     * @return the resulting executor
     */
    default Executor contextAwareExecutor(Executor delegate) {
        return command -> delegate.execute(WorkflowExecutionContext.capture().wrap(command));
    }

    /**
     * Returns a read-only view of snapshots exposed by this engine.
     * @return a read-only view of snapshots exposed by this engine
     */
    WorkflowEngineContext context();

    /**
     * Returns the persistence query service. Engines without this capability throw UnsupportedOperationException.
     * @return the persistence query service
     */
    default org.jworkflow.query.WorkflowQueryService queries() {
        throw new UnsupportedOperationException("A persistence-backed query service is not configured");
    }

    /**
     * Starts a workflow through the command boundary. Explicit command metadata can request idempotent replay;
     * convenience overloads create default metadata without a replay key.
     * @param command command to validate and execute
     * @return the start outcome and instance identity
     */
    StartWorkflowResult start(StartWorkflowCommand command);

    /**
     * Applies an input signal to an existing instance and validates its current workflow state.
     * @param command command to validate and execute
     * @return the command outcome and snapshot
     */
    WorkflowCommandResult signal(SignalWorkflowCommand command);

    /**
     * Requests another execution of a failed step. Nontransactional handler effects may repeat.
     * @param command command to validate and execute
     * @return the command outcome and snapshot
     */
    WorkflowCommandResult retryFailedStep(RetryFailedStepCommand command);

    /**
     * Cancels the instance through the command boundary, including its relevant pending timers.
     * @param command command to validate and execute
     * @return the command outcome and snapshot
     */
    WorkflowCommandResult cancel(CancelWorkflowCommand command);

    /**
     * Resumes an instance through the command boundary after validating the current state.
     * @param command command to validate and execute
     * @return the command outcome and snapshot
     */
    WorkflowCommandResult resume(ResumeWorkflowCommand command);

    /**
     * {@inheritDoc}
     */
    @Override
    default org.jworkflow.routing.WorkflowRoutingResult route(
            WorkflowEvent event, org.jworkflow.routing.WorkflowEventRoute route) {
        throw new UnsupportedOperationException("Explicit event routing is not supported by this workflow engine");
    }

    /**
     * Starts a workflow through the command boundary. Explicit command metadata can request idempotent replay;
     * convenience overloads create default metadata without a replay key.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @param variables workflow variable values; durable values must follow the supported JSON value model
     * @return the resulting workflow instance id
     */
    default WorkflowInstanceId start(String workflowKey, String businessKey, Map<String, Object> variables) {
        return start(new StartWorkflowCommand(
                workflowKey,
                null,
                businessKey,
                variables,
                WorkflowCommandMetadata.defaults(workflowKey, null, null, businessKey))).workflowInstanceId();
    }

    /**
     * Starts with the supplied workflow ID as business key and as the workflowId/businessKey initial variables.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param workflowId workflow instance identity or business identifier used by the operation
     * @return the resulting workflow instance id
     */
    default WorkflowInstanceId createWorkflow(String workflowKey, String workflowId) {
        return start(workflowKey, workflowId, Map.of("workflowId", workflowId, "businessKey", workflowId));
    }

    /**
     * Applies an input signal to an existing instance and validates its current workflow state.
     * @param instanceId workflow instance identity
     * @param signal triggering signal and its original message envelope
     */
    default void signal(WorkflowInstanceId instanceId, WorkflowSignal signal) {
        signal(new SignalWorkflowCommand(
                instanceId,
                signal,
                WorkflowCommandMetadata.defaults(null, null, instanceId, signal == null ? null : signal.businessKey())));
    }

    /**
     * Requests another execution of a failed step. Nontransactional handler effects may repeat.
     * @param instanceId workflow instance identity
     * @param stepId failed step to retry; null where the engine may infer it
     */
    default void retryFailedStep(WorkflowInstanceId instanceId, String stepId) {
        retryFailedStep(new RetryFailedStepCommand(
                instanceId,
                stepId,
                WorkflowCommandMetadata.defaults(null, null, instanceId, null)));
    }

    /**
     * Cancels the instance through the command boundary, including its relevant pending timers.
     * @param instanceId workflow instance identity
     */
    default void cancel(WorkflowInstanceId instanceId) {
        cancel(new CancelWorkflowCommand(
                instanceId,
                WorkflowCommandMetadata.defaults(null, null, instanceId, null)));
    }

    /**
     * Resumes an instance through the command boundary after validating the current state.
     * @param instanceId workflow instance identity
     */
    default void resume(WorkflowInstanceId instanceId) {
        resume(new ResumeWorkflowCommand(
                instanceId,
                WorkflowCommandMetadata.defaults(null, null, instanceId, null)));
    }

    /**
     * Reads the currently visible instance snapshot; fails when the requested instance does not exist.
     * @param instanceId workflow instance identity
     * @return the instance snapshot
     */
    WorkflowSnapshot snapshot(WorkflowInstanceId instanceId);

    /**
     * Reads the currently visible instance snapshot; fails when the requested instance does not exist.
     * @param workflowKey registered workflow name used to resolve a definition
     * @param businessKey application business identity associated with the workflow
     * @return the instance snapshot
     */
    WorkflowSnapshot snapshot(String workflowKey, String businessKey);

    /**
     * {@inheritDoc}
     */
    @Override
    default void close() {
    }
}
