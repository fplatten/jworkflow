package org.jworkflow.events;


import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated subject.action event name. Construction trims whitespace, converts to lower case using Locale.ROOT and
 *  requires a past-tense action ending in ed or equal to taken.
 * @param value subject.action name, normalized to lower case and validated as past tense
 */
public record EventName(String value) {
    private static final Pattern TAXONOMY_PATTERN = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    /**
     * Creates this value from the supplied components.
     * @param value subject.action name, normalized to lower case and validated as past tense
     * @throws NullPointerException if value is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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

    /**
     * Returns the subject before the dot in the normalized subject.action name.
     * @return the subject before the dot in the normalized subject.action name
     */
    public String subject() {
        return subject(value);
    }

    /**
     * Returns the action after the dot in the normalized subject.action name.
     * @return the action after the dot in the normalized subject.action name
     */
    public String action() {
        return action(value);
    }

    private static String subject(String value) {
        return value.substring(0, value.indexOf('.'));
    }

    private static String action(String value) {
        return value.substring(value.indexOf('.') + 1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return value;
    }
}
