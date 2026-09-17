package org.jworkflow.events;

/** Identity of the command or event that caused another event. */
public record CausationId(String value) {
    public CausationId { value = EventIdentity.require(value, "causationId"); }
    public static CausationId of(String value) { return value == null ? null : new CausationId(value); }
    @Override public String toString() { return value; }
}
