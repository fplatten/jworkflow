package org.jworkflow.events;

/** Stable correlation identity shared by related commands and events. */
public record CorrelationId(String value) {
    public CorrelationId { value = EventIdentity.require(value, "correlationId"); }
    public static CorrelationId of(String value) { return value == null ? null : new CorrelationId(value); }
    @Override public String toString() { return value; }
}
