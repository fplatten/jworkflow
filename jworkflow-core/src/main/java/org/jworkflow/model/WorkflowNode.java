package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.List;
import java.util.Objects;

public record WorkflowNode(
        String name,
        WorkflowNodeType type,
        String action,
        String listenerClassName,
        String listenerMethodName,
        SubWorkflowDefinition subWorkflow,
        ForkDefinition fork,
        JoinDefinition join,
        GatewayType gatewayType,
        LoopDefinition loop,
        WaitDefinition waitDefinition,
        RetryPolicy retryPolicy,
        TimeoutDefinition timeout,
        List<WorkflowTransition> transitions,
        ListenerInvocation listenerInvocation
) {
    public WorkflowNode(
            String name, WorkflowNodeType type, String action, String listenerClassName, String listenerMethodName,
            SubWorkflowDefinition subWorkflow, ForkDefinition fork, JoinDefinition join, GatewayType gatewayType,
            LoopDefinition loop, WaitDefinition waitDefinition, RetryPolicy retryPolicy, TimeoutDefinition timeout,
            List<WorkflowTransition> transitions
    ) {
        this(name, type, action, listenerClassName, listenerMethodName, subWorkflow, fork, join, gatewayType,
                loop, waitDefinition, retryPolicy, timeout, transitions, null);
    }

    public WorkflowNode {
        requireText(name, "name");
        Objects.requireNonNull(type, "type");
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        if (type == WorkflowNodeType.SUB_WORKFLOW) {
            Objects.requireNonNull(subWorkflow, "subWorkflow");
        }
        if (type == WorkflowNodeType.FORK) {
            Objects.requireNonNull(fork, "fork");
        }
        if (type == WorkflowNodeType.JOIN) {
            Objects.requireNonNull(join, "join");
        }
        if (type == WorkflowNodeType.GATEWAY) {
            Objects.requireNonNull(gatewayType, "gatewayType");
        }
        if (type == WorkflowNodeType.LOOP) {
            Objects.requireNonNull(loop, "loop");
        }
        if (type == WorkflowNodeType.WAIT) {
            Objects.requireNonNull(waitDefinition, "waitDefinition");
        }
    }

    public static WorkflowNode step(String name, String action, List<WorkflowTransition> transitions) {
        requireText(action, "action");
        return new WorkflowNode(name, WorkflowNodeType.STEP, action, null, null, null, null, null, null, null, null, null, null, transitions, null);
    }

    public static WorkflowNode subWorkflow(String name, SubWorkflowDefinition subWorkflow) {
        return new WorkflowNode(name, WorkflowNodeType.SUB_WORKFLOW, null, null, null, subWorkflow, null, null, null, null, null, null, null, List.of(), null);
    }

    public static WorkflowNode fork(String name, ForkDefinition fork) {
        return new WorkflowNode(name, WorkflowNodeType.FORK, null, null, null, null, fork, null, null, null, null, null, null, List.of(), null);
    }

    public static WorkflowNode join(String name, JoinDefinition join) {
        return new WorkflowNode(name, WorkflowNodeType.JOIN, null, null, null, null, null, join, null, null, null, null, null, List.of(), null);
    }

    public static WorkflowNode waitFor(String name, WaitDefinition wait, TimeoutDefinition timeout) {
        return new WorkflowNode(name, WorkflowNodeType.WAIT, null, null, null, null, null, null, null, null, wait, null, timeout, List.of(), null);
    }

    public static WorkflowNode end(String name) {
        return new WorkflowNode(name, WorkflowNodeType.END, null, null, null, null, null, null, null, null, null, null, null, List.of(), null);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
