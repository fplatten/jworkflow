package org.jworkflow.model;


/**
 * Bounded loop with a declarative continuation condition, body target and exit target.
 * @param whileCondition loop continuation condition
 * @param maxIterations positive loop iteration limit
 * @param stepNode loop body node
 * @param nextNode continuation node after this operation completes
 */
public record LoopDefinition(
        BranchCondition whileCondition,
        int maxIterations,
        String stepNode,
        String nextNode
) {
    /**
     * Creates this value from the supplied components.
     * @param whileCondition loop continuation condition
     * @param maxIterations positive loop iteration limit
     * @param stepNode loop body node
     * @param nextNode continuation node after this operation completes
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public LoopDefinition {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
    }
}
