package org.jworkflow.events;

final class EventIdentity {
    private EventIdentity() {}
    static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > 512) throw new IllegalArgumentException(name + " must not exceed 512 characters");
        return value;
    }
}
