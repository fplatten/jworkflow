package org.jworkflow.dsl;

import java.util.List;

/**
 * Carries structured diagnostics for a DSL compilation failure. Submitted script text is not executed to recover
 * from a compilation error.
 */
public final class DslCompilationException extends RuntimeException {
    private final transient List<DslDiagnostic> diagnostics;

    /**
     * Creates a dsl compilation exception with the supplied diagnostic context.
     * @param diagnostics structured compiler diagnostics
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public DslCompilationException(List<DslDiagnostic> diagnostics) {
        super(message(diagnostics));
        if (diagnostics == null || diagnostics.isEmpty()) {
            throw new IllegalArgumentException("diagnostics are required");
        }
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Returns the structured compiler diagnostics associated with this failure.
     * @return the matching values in the order defined by this operation
     */
    public List<DslDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String message(List<DslDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "Groovy DSL compilation failed";
        }
        DslDiagnostic first = diagnostics.get(0);
        return "Groovy DSL compilation failed at " + first.position().source() + ":"
                + first.position().line() + ":" + first.position().column() + ": " + first.message();
    }
}
