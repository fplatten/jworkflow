package org.jworkflow.dsl;

import java.util.Objects;

public record DslDiagnostic(
        String code,
        DslDiagnosticCategory category,
        DslSourcePosition position,
        String construct,
        String message
) {
    public DslDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(position, "position");
        construct = construct == null ? "" : construct;
        Objects.requireNonNull(message, "message");
    }
}
