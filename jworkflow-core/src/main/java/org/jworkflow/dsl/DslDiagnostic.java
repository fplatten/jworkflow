package org.jworkflow.dsl;

import java.util.Objects;

/**
 * A compiler diagnostic with a stable category, source position, rejected construct and human-readable
 *  explanation.
 * @param code stable diagnostic code
 * @param category diagnostic or SQL failure category
 * @param position source coordinates of the diagnostic
 * @param construct DSL construct associated with the diagnostic
 * @param message human-readable diagnostic detail
 */
public record DslDiagnostic(
        String code,
        DslDiagnosticCategory category,
        DslSourcePosition position,
        String construct,
        String message
) {
    /**
     * Creates this value from the supplied components.
     * @param code stable diagnostic code
     * @param category diagnostic or SQL failure category
     * @param position source coordinates of the diagnostic
     * @param construct DSL construct associated with the diagnostic
     * @param message human-readable diagnostic detail
     * @throws NullPointerException if code, category, position, message is null
     */
    public DslDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(position, "position");
        construct = construct == null ? "" : construct;
        Objects.requireNonNull(message, "message");
    }
}
