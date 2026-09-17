package org.jworkflow.dsl;

import java.util.List;

public final class DslCompilationException extends RuntimeException {
    private final List<DslDiagnostic> diagnostics;

    public DslCompilationException(List<DslDiagnostic> diagnostics) {
        super(message(diagnostics));
        if (diagnostics == null || diagnostics.isEmpty()) {
            throw new IllegalArgumentException("diagnostics are required");
        }
        this.diagnostics = List.copyOf(diagnostics);
    }

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
