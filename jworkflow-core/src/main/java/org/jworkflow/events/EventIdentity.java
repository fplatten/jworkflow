package org.jworkflow.events;

/**
 * Shared validation for nonblank identity strings limited to 512 characters; it does not normalize or rewrite
 * their content.
 */
final class EventIdentity {
    private EventIdentity() {}
    static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > 512) throw new IllegalArgumentException(name + " must not exceed 512 characters");
        return value;
    }
}
