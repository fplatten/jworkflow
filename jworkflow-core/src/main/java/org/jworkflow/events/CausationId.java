package org.jworkflow.events;

/**
 * Identity of the command or event that caused another event.
 * @param value nonblank identity of at most 512 characters
 */
public record CausationId(String value) {
    /**
     * Creates this value from the supplied components.
     * @param value nonblank identity of at most 512 characters
     */
    public CausationId { value = EventIdentity.require(value, "causationId"); }
    /**
     * Wraps a causation identifier, preserving null as absence.
     * @param value nonblank identity of at most 512 characters; null preserves absence
     * @return the resulting causation id
     */
    public static CausationId of(String value) { return value == null ? null : new CausationId(value); }
    /**
     * {@inheritDoc}
     */
    @Override public String toString() { return value; }
}
