package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Main command API for starting and advancing workflow instances.
 */
public interface WorkflowEngine extends EventPublisher, org.jworkflow.routing.WorkflowEventRouter, AutoCloseable {
    enum Type {
        IN_MEMORY,
        SQLITE
    }

    static WorkflowEngineBuilder builder() {
        return new WorkflowEngineBuilder();
    }

    static WorkflowEngine create(WorkflowEngineProperties properties) throws ClassNotFoundException {
        return builder().properties(properties).build();
    }

    static WorkflowEngine instance() {
        return WorkflowEngines.instance();
    }

    static void setInstance(WorkflowEngine engine) {
        WorkflowEngines.setInstance(engine);
    }

    static void clearInstance() {
        WorkflowEngines.clearInstance();
    }

    default void registerListener(String listenerId, Object listener) {
        throw new UnsupportedOperationException("Listener registration is not supported by this workflow engine");
    }

    default Executor contextAwareExecutor(Executor delegate) {
        return command -> delegate.execute(WorkflowExecutionContext.capture().wrap(command));
    }

    WorkflowEngineContext context();

    default org.jworkflow.query.WorkflowQueryService queries() {
        throw new UnsupportedOperationException("A persistence-backed query service is not configured");
    }

    StartWorkflowResult start(StartWorkflowCommand command);

    WorkflowCommandResult signal(SignalWorkflowCommand command);

    WorkflowCommandResult retryFailedStep(RetryFailedStepCommand command);

    WorkflowCommandResult cancel(CancelWorkflowCommand command);

    WorkflowCommandResult resume(ResumeWorkflowCommand command);

    @Override
    default org.jworkflow.routing.WorkflowRoutingResult route(
            WorkflowEvent event, org.jworkflow.routing.WorkflowEventRoute route) {
        throw new UnsupportedOperationException("Explicit event routing is not supported by this workflow engine");
    }

    default WorkflowInstanceId start(String workflowKey, String businessKey, Map<String, Object> variables) {
        return start(new StartWorkflowCommand(
                workflowKey,
                null,
                businessKey,
                variables,
                WorkflowCommandMetadata.defaults(workflowKey, null, null, businessKey))).workflowInstanceId();
    }

    default WorkflowInstanceId createWorkflow(String workflowKey, String workflowId) {
        return start(workflowKey, workflowId, Map.of("workflowId", workflowId, "businessKey", workflowId));
    }

    default void signal(WorkflowInstanceId instanceId, WorkflowSignal signal) {
        signal(new SignalWorkflowCommand(
                instanceId,
                signal,
                WorkflowCommandMetadata.defaults(null, null, instanceId, signal == null ? null : signal.businessKey())));
    }

    default void retryFailedStep(WorkflowInstanceId instanceId, String stepId) {
        retryFailedStep(new RetryFailedStepCommand(
                instanceId,
                stepId,
                WorkflowCommandMetadata.defaults(null, null, instanceId, null)));
    }

    default void cancel(WorkflowInstanceId instanceId) {
        cancel(new CancelWorkflowCommand(
                instanceId,
                WorkflowCommandMetadata.defaults(null, null, instanceId, null)));
    }

    default void resume(WorkflowInstanceId instanceId) {
        resume(new ResumeWorkflowCommand(
                instanceId,
                WorkflowCommandMetadata.defaults(null, null, instanceId, null)));
    }

    WorkflowSnapshot snapshot(WorkflowInstanceId instanceId);

    WorkflowSnapshot snapshot(String workflowKey, String businessKey);

    @Override
    default void close() {
    }
}
