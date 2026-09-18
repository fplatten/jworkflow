package org.jworkflow.model;


public record LoopDefinition(
        BranchCondition whileCondition,
        int maxIterations,
        String stepNode,
        String nextNode
) {
    public LoopDefinition {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
    }
}
