package org.jworkflow.events;

/** Application-supplied distributed tracing identity. */
public record TraceId(String value) {
    public TraceId { value = EventIdentity.require(value, "traceId"); }
    public static TraceId of(String value) { return value == null ? null : new TraceId(value); }
    @Override public String toString() { return value; }
}
