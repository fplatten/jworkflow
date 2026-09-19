package org.jworkflow.dsl;

/**
 * Distinguishes parse errors, forbidden constructs, grammar violations, resource limits and invalid workflow
 * models.
 */
public enum DslDiagnosticCategory {
    /**
     * The source cannot be parsed.
     */
    SYNTAX,
    /**
     * The source contains a forbidden construct.
     */
    SECURITY,
    /**
     * The source does not follow the supported workflow grammar.
     */
    GRAMMAR,
    /**
     * A configured source or AST complexity limit was exceeded.
     */
    RESOURCE_LIMIT,
    /**
     * The resulting workflow model violates structural constraints.
     */
    DOMAIN_VALIDATION
}
