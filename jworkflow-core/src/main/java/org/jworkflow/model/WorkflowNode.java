package org.jworkflow.model;


import java.util.List;
import java.util.Objects;

/**
 * Immutable declarative node with type-specific action, routing, retry, timeout and child-workflow settings. Use
 *  the matching factory and validate the completed graph before activation.
 * @param name unique node name within the workflow
 * @param type declarative node behavior
 * @param action registered handler action name
 * @param listenerClassName registered infrastructure listener type name
 * @param listenerMethodName declared listener method name
 * @param subWorkflow child workflow reference and input mapping
 * @param fork parallel branch configuration
 * @param join required branch completion configuration
 * @param gatewayType gateway branch selection policy
 * @param loop bounded loop configuration
 * @param waitDefinition declarative expected-event and continuation configuration
 * @param retryPolicy attempt budget and retry-deadline policy
 * @param timeout step or wait timeout configuration, when present
 * @param transitions named outgoing node transitions
 * @param listenerInvocation declarative listener method and argument specification
 */
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
    /**
     * Creates this value from the supplied components.
     * @param name unique node name within the workflow
     * @param type declarative node behavior
     * @param action registered handler action name
     * @param listenerClassName registered infrastructure listener type name
     * @param listenerMethodName declared listener method name
     * @param subWorkflow child workflow reference and input mapping
     * @param fork parallel branch configuration
     * @param join required branch completion configuration
     * @param gatewayType gateway branch selection policy
     * @param loop bounded loop configuration
     * @param waitDefinition declarative expected-event and continuation configuration
     * @param retryPolicy attempt budget and retry-deadline policy
     * @param timeout step or wait timeout configuration, when present
     * @param transitions named outgoing node transitions
     */
    public WorkflowNode(
            String name, WorkflowNodeType type, String action, String listenerClassName, String listenerMethodName,
            SubWorkflowDefinition subWorkflow, ForkDefinition fork, JoinDefinition join, GatewayType gatewayType,
            LoopDefinition loop, WaitDefinition waitDefinition, RetryPolicy retryPolicy, TimeoutDefinition timeout,
            List<WorkflowTransition> transitions
    ) {
        this(name, type, action, listenerClassName, listenerMethodName, subWorkflow, fork, join, gatewayType,
                loop, waitDefinition, retryPolicy, timeout, transitions, null);
    }

    /**
     * Creates this value from the supplied components.
     * @param name unique node name within the workflow
     * @param type declarative node behavior
     * @param action registered handler action name
     * @param listenerClassName registered infrastructure listener type name
     * @param listenerMethodName declared listener method name
     * @param subWorkflow child workflow reference and input mapping
     * @param fork parallel branch configuration
     * @param join required branch completion configuration
     * @param gatewayType gateway branch selection policy
     * @param loop bounded loop configuration
     * @param waitDefinition declarative expected-event and continuation configuration
     * @param retryPolicy attempt budget and retry-deadline policy
     * @param timeout step or wait timeout configuration, when present
     * @param transitions named outgoing node transitions
     * @param listenerInvocation declarative listener method and argument specification
     * @throws NullPointerException if type, subWorkflow, fork, join, gatewayType, loop, waitDefinition is null
     */
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

    /**
     * Creates a handler step with explicit outgoing transitions.
     * @param name unique node name within the workflow
     * @param action registered handler action name
     * @param transitions named outgoing node transitions
     * @return the resulting workflow node
     */
    public static WorkflowNode step(String name, String action, List<WorkflowTransition> transitions) {
        requireText(action, "action");
        return new WorkflowNode(name, WorkflowNodeType.STEP, action, null, null, null, null, null, null, null, null, null, null, transitions, null);
    }

    /**
     * Creates a node that invokes the supplied child workflow definition.
     * @param name unique node name within the workflow
     * @param subWorkflow child workflow reference and input mapping
     * @return the resulting workflow node
     */
    public static WorkflowNode subWorkflow(String name, SubWorkflowDefinition subWorkflow) {
        return new WorkflowNode(name, WorkflowNodeType.SUB_WORKFLOW, null, null, null, subWorkflow, null, null, null, null, null, null, null, List.of(), null);
    }

    /**
     * Creates a node that starts the declared parallel branches.
     * @param name unique node name within the workflow
     * @param fork parallel branch configuration
     * @return the resulting workflow node
     */
    public static WorkflowNode fork(String name, ForkDefinition fork) {
        return new WorkflowNode(name, WorkflowNodeType.FORK, null, null, null, null, fork, null, null, null, null, null, null, List.of(), null);
    }

    /**
     * Creates a node that waits for the declared branch completions.
     * @param name unique node name within the workflow
     * @param join required branch completion configuration
     * @return the resulting workflow node
     */
    public static WorkflowNode join(String name, JoinDefinition join) {
        return new WorkflowNode(name, WorkflowNodeType.JOIN, null, null, null, null, null, join, null, null, null, null, null, List.of(), null);
    }

    /**
     * Creates an external-event wait with an optional timeout.
     * @param name unique node name within the workflow
     * @param wait external-event wait configuration
     * @param timeout step or wait timeout configuration, when present
     * @return the resulting workflow node
     */
    public static WorkflowNode waitFor(String name, WaitDefinition wait, TimeoutDefinition timeout) {
        return new WorkflowNode(name, WorkflowNodeType.WAIT, null, null, null, null, null, null, null, null, wait, null, timeout, List.of(), null);
    }

    /**
     * Creates a terminal node with no outgoing transitions.
     * @param name unique node name within the workflow
     * @return the resulting workflow node
     */
    public static WorkflowNode end(String name) {
        return new WorkflowNode(name, WorkflowNodeType.END, null, null, null, null, null, null, null, null, null, null, null, List.of(), null);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
