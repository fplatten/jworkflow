package org.jworkflow.dsl;

public record DslSourcePosition(
        String source,
        int line,
        int column,
        int lastLine,
        int lastColumn
) {
    public DslSourcePosition {
        source = source == null ? "<unknown>" : source;
        line = Math.max(line, 1);
        column = Math.max(column, 1);
        lastLine = Math.max(lastLine, line);
        lastColumn = Math.max(lastColumn, column);
    }
}
