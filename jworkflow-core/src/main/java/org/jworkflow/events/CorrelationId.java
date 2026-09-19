package org.jworkflow.events;

/**
 * Stable correlation identity shared by related commands and events.
 * @param value nonblank identity of at most 512 characters
 */
public record CorrelationId(String value) {
    /**
     * Creates this value from the supplied components.
     * @param value nonblank identity of at most 512 characters
     */
    public CorrelationId { value = EventIdentity.require(value, "correlationId"); }
    /**
     * Wraps a correlation identifier, preserving null as absence.
     * @param value nonblank identity of at most 512 characters; null preserves absence
     * @return the resulting correlation id
     */
    public static CorrelationId of(String value) { return value == null ? null : new CorrelationId(value); }
    /**
     * {@inheritDoc}
     */
    @Override public String toString() { return value; }
}
