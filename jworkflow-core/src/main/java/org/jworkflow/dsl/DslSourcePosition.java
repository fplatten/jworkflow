package org.jworkflow.dsl;

/**
 * One-based source coordinates attached to a DSL diagnostic. Missing source names become an unknown marker;
 *  coordinates are clamped to at least one and end coordinates cannot precede their starts.
 * @param source source name used in diagnostics; null becomes &lt;unknown&gt;
 * @param line one-based start line
 * @param column one-based start column
 * @param lastLine one-based end line
 * @param lastColumn one-based end column
 */
public record DslSourcePosition(
        String source,
        int line,
        int column,
        int lastLine,
        int lastColumn
) {
    /**
     * Creates this value from the supplied components.
     * @param source source name used in diagnostics; null becomes &lt;unknown&gt;
     * @param line one-based start line
     * @param column one-based start column
     * @param lastLine one-based end line
     * @param lastColumn one-based end column
     */
    public DslSourcePosition {
        source = source == null ? "<unknown>" : source;
        line = Math.max(line, 1);
        column = Math.max(column, 1);
        lastLine = Math.max(lastLine, line);
        lastColumn = Math.max(lastColumn, column);
    }
}
