package org.jworkflow.dsl;

/**
 * Positive resource limits applied while reading and interpreting a Groovy workflow definition. These limits bound
 * accepted source/AST complexity; they are not an operating-system sandbox.
 * @param maxSourceCharacters positive maximum characters in submitted source
 * @param maxAstNodes positive maximum number of accepted AST nodes
 * @param maxAstDepth positive maximum AST nesting depth
 * @param maxCollectionEntries positive maximum entries in a DSL collection
 * @param maxWorkflowNodes positive maximum nodes in the compiled workflow
 * @param maxTransitions positive maximum transitions in the compiled workflow
 * @param maxStringLength positive maximum characters in a string literal
 * @param maxNumericDigits positive maximum digits in a numeric literal
 */
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
    /**
     * Default bounded DSL compiler options.
     */
    public static final DslCompilerOptions DEFAULT = new DslCompilerOptions(
            256_000, 20_000, 64, 1_000, 2_000, 10_000, 32_000, 128);

    /**
     * Creates this value from the supplied components.
     * @param maxSourceCharacters positive maximum characters in submitted source
     * @param maxAstNodes positive maximum number of accepted AST nodes
     * @param maxAstDepth positive maximum AST nesting depth
     * @param maxCollectionEntries positive maximum entries in a DSL collection
     * @param maxWorkflowNodes positive maximum nodes in the compiled workflow
     * @param maxTransitions positive maximum transitions in the compiled workflow
     * @param maxStringLength positive maximum characters in a string literal
     * @param maxNumericDigits positive maximum digits in a numeric literal
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public DslCompilerOptions {
        if (maxSourceCharacters < 1 || maxAstNodes < 1 || maxAstDepth < 1
                || maxCollectionEntries < 1 || maxWorkflowNodes < 1 || maxTransitions < 1
                || maxStringLength < 1 || maxNumericDigits < 1) {
            throw new IllegalArgumentException("All DSL compiler limits must be positive");
        }
    }
}
