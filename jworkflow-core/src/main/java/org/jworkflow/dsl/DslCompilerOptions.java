package org.jworkflow.dsl;

public record DslCompilerOptions(
        int maxSourceCharacters,
        int maxAstNodes,
        int maxAstDepth,
        int maxCollectionEntries,
        int maxWorkflowNodes,
        int maxTransitions,
        int maxStringLength,
        int maxNumericDigits
) {
    public static final DslCompilerOptions DEFAULT = new DslCompilerOptions(
            256_000, 20_000, 64, 1_000, 2_000, 10_000, 32_000, 128);

    public DslCompilerOptions {
        if (maxSourceCharacters < 1 || maxAstNodes < 1 || maxAstDepth < 1
                || maxCollectionEntries < 1 || maxWorkflowNodes < 1 || maxTransitions < 1
                || maxStringLength < 1 || maxNumericDigits < 1) {
            throw new IllegalArgumentException("All DSL compiler limits must be positive");
        }
    }
}
