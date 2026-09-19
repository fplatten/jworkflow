package org.jworkflow.events;

/**
 * Application-supplied distributed tracing identity.
 * @param value nonblank identity of at most 512 characters
 */
public record TraceId(String value) {
    /**
     * Creates this value from the supplied components.
     * @param value nonblank identity of at most 512 characters
     */
    public TraceId { value = EventIdentity.require(value, "traceId"); }
    /**
     * Wraps a trace identifier, preserving null as absence.
     * @param value nonblank identity of at most 512 characters; null preserves absence
     * @return the resulting trace id
     */
    public static TraceId of(String value) { return value == null ? null : new TraceId(value); }
    /**
     * {@inheritDoc}
     */
    @Override public String toString() { return value; }
}
