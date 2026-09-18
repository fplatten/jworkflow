package org.jworkflow.model;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DefinitionValidator {
    public DefinitionValidationResult validate(WorkflowDefinition definition) {
        ArrayList<DefinitionValidationError> errors = new ArrayList<>();
        if (definition == null) {
            errors.add(error("definition.required", "Workflow definition is required", null));
            return new DefinitionValidationResult(errors);
        }

        Map<String, WorkflowNode> nodes = definition.nodes();
        if (nodes.isEmpty()) {
            errors.add(error("nodes.required", "Workflow definition must contain at least one node", definition.name()));
            return new DefinitionValidationResult(errors);
        }
        if (!nodes.containsKey(definition.startNode())) {
            errors.add(error("start.unknown", "Start node does not exist: " + definition.startNode(), definition.startNode()));
        }

        boolean hasTerminal = false;
        for (WorkflowNode node : nodes.values()) {
            if (node.type() == WorkflowNodeType.END) {
                hasTerminal = true;
            }
            validateNode(node, nodes, errors);
        }
        if (!hasTerminal) {
            errors.add(error("terminal.required", "Workflow definition must contain at least one terminal node", definition.name()));
        } else {
            Set<String> reachable = reachableNodes(definition.startNode(), nodes);
            if (nodes.values().stream().noneMatch(node -> reachable.contains(node.name())
                    && node.type() == WorkflowNodeType.END)) {
                errors.add(error("terminal.unreachable", "No terminal node is reachable from the start node", definition.name()));
            }
        }

        return new DefinitionValidationResult(errors);
    }

    public DefinitionValidationResult validate(WorkflowDefinition definition, WorkflowDefinitionRegistry registry) {
        DefinitionValidationResult base = validate(definition);
        ArrayList<DefinitionValidationError> errors = new ArrayList<>(base.errors());
        if (definition != null && registry != null) {
            for (WorkflowNode node : definition.nodes().values()) {
                if (node.subWorkflow() != null
                        && registry.find(
                        node.subWorkflow().workflowName(),
                        node.subWorkflow().workflowVersion()).isEmpty()) {
                    errors.add(error(
                            "subworkflow.unknown",
                            "Sub-workflow target does not exist: "
                                    + node.subWorkflow().workflowName()
                                    + ":"
                                    + node.subWorkflow().workflowVersion(),
                            node.name()));
                }
            }
        }
        return new DefinitionValidationResult(errors);
    }

    private static void validateNode(
            WorkflowNode node,
            Map<String, WorkflowNode> nodes,
            ArrayList<DefinitionValidationError> errors
    ) {
        Set<String> targets = new HashSet<>();
        for (WorkflowTransition transition : node.transitions()) {
            if (!nodes.containsKey(transition.targetNode())) {
                errors.add(error(
                        "transition.target.unknown",
                        "Transition target does not exist: " + transition.targetNode(),
                        node.name()));
            }
            if (!targets.add(transition.targetNode())) {
                errors.add(error(
                        "transition.target.duplicate",
                        "Duplicate transition target from node: " + transition.targetNode(),
                        node.name()));
            }
        }
        validateStructuredNode(node, nodes, errors);
        if (node.type() == WorkflowNodeType.GATEWAY && node.transitions().isEmpty()) {
            errors.add(error("gateway.transitions.required", "Gateway must declare at least one route", node.name()));
        }
    }

    private static void validateStructuredNode(
            WorkflowNode node,
            Map<String, WorkflowNode> nodes,
            ArrayList<DefinitionValidationError> errors
    ) {
        if (node.subWorkflow() != null) {
            validateOptionalTarget("subworkflow.success.target", node.subWorkflow().successTargetNode(), nodes, node.name(), errors);
            validateOptionalTarget("subworkflow.failure.target", node.subWorkflow().failureTargetNode(), nodes, node.name(), errors);
        }
        if (node.loop() != null) {
            validateOptionalTarget("loop.step.target", node.loop().stepNode(), nodes, node.name(), errors);
            validateOptionalTarget("loop.next.target", node.loop().nextNode(), nodes, node.name(), errors);
        }
        validateFork(node, nodes, errors);
        validateJoin(node, nodes, errors);
        if (node.waitDefinition() != null) {
            validateOptionalTarget("wait.next.target", node.waitDefinition().targetNode(), nodes, node.name(), errors);
        }
        if (node.timeout() != null) {
            validateOptionalTarget("timeout.target", node.timeout().targetNode(), nodes, node.name(), errors);
        }
    }

    private static void validateFork(
            WorkflowNode node,
            Map<String, WorkflowNode> nodes,
            ArrayList<DefinitionValidationError> errors
    ) {
        if (node.fork() != null) {
            validateOptionalTarget("fork.join.target", node.fork().joinNode(), nodes, node.name(), errors);
            for (String branchTarget : node.fork().branches().values()) {
                validateOptionalTarget("fork.branch.target", branchTarget, nodes, node.name(), errors);
            }
            WorkflowNode joinNode = nodes.get(node.fork().joinNode());
            if (joinNode != null && joinNode.join() != null) {
                for (String requiredBranch : joinNode.join().requiredBranches()) {
                    if (!node.fork().branches().containsKey(requiredBranch)) {
                        errors.add(error(
                                "join.branch.unknown",
                                "Join requires a branch not declared by fork " + node.name() + ": " + requiredBranch,
                                joinNode.name()));
                    }
                }
            }
        }
    }

    private static void validateJoin(
            WorkflowNode node,
            Map<String, WorkflowNode> nodes,
            ArrayList<DefinitionValidationError> errors
    ) {
        if (node.join() != null) {
            validateOptionalTarget("join.next.target", node.join().nextNode(), nodes, node.name(), errors);
            if (node.join().requiredBranches().isEmpty()) {
                errors.add(error("join.branches.required", "Join must require at least one branch", node.name()));
            }
        }
    }

    private static Set<String> reachableNodes(String start, Map<String, WorkflowNode> nodes) {
        Set<String> reachable = new HashSet<>();
        ArrayList<String> pending = new ArrayList<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String name = pending.remove(pending.size() - 1);
            WorkflowNode node = nodes.get(name);
            if (reachable.add(name) && node != null) {
                addReachableTargets(node, pending);
            }
        }
        return reachable;
    }

    private static void addReachableTargets(WorkflowNode node, ArrayList<String> pending) {
        node.transitions().forEach(transition -> pending.add(transition.targetNode()));
        if (node.waitDefinition() != null) pending.add(node.waitDefinition().targetNode());
        if (node.timeout() != null && node.timeout().targetNode() != null) pending.add(node.timeout().targetNode());
        if (node.loop() != null) {
            pending.add(node.loop().stepNode());
            pending.add(node.loop().nextNode());
        }
        if (node.fork() != null) {
            pending.addAll(node.fork().branches().values());
            pending.add(node.fork().joinNode());
        }
        if (node.join() != null) pending.add(node.join().nextNode());
        if (node.subWorkflow() != null) {
            pending.add(node.subWorkflow().successTargetNode());
            pending.add(node.subWorkflow().failureTargetNode());
        }
    }

    private static void validateOptionalTarget(
            String code,
            String target,
            Map<String, WorkflowNode> nodes,
            String location,
            ArrayList<DefinitionValidationError> errors
    ) {
        if (target != null && !target.isBlank() && !nodes.containsKey(target)) {
            errors.add(error(code, "Target does not exist: " + target, location));
        }
    }

    private static DefinitionValidationError error(String code, String message, String location) {
        return new DefinitionValidationError(code, message, location);
    }
}
