package org.jworkflow.events;


import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EventName(String value) {
    private static final Pattern TAXONOMY_PATTERN = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    public EventName {
        Objects.requireNonNull(value, "value");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!TAXONOMY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Event name must use subject.action taxonomy: " + value);
        }
        if (!action(value).endsWith("ed") && !"taken".equals(action(value))) {
            throw new IllegalArgumentException("Event action should be past tense: " + value);
        }
    }

    public String subject() {
        return subject(value);
    }

    public String action() {
        return action(value);
    }

    private static String subject(String value) {
        return value.substring(0, value.indexOf('.'));
    }

    private static String action(String value) {
        return value.substring(value.indexOf('.') + 1);
    }

    @Override
    public String toString() {
        return value;
    }
}
