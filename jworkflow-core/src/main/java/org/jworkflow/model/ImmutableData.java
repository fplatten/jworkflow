package org.jworkflow.model;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;

/** Defensive-copy support for event payloads and workflow variables. */
public final class ImmutableData {
    private ImmutableData() {
    }

    /**
     * Defensively copies a supported string-keyed variable map; null becomes an empty immutable map.
     * @param values values to defensively copy or encode
     * @return the resulting key/value mapping
     */
    public static Map<String, Object> copyStringObjectMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, copy(value)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Recursively copies supported containers and arrays to isolate stored values from their inputs.
     * @param value the value to encode or copy
     * @return the resulting object
     */
    public static Object copy(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character
                || value instanceof Enum<?> || value instanceof UUID
                || value instanceof TemporalAccessor) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(copy(key), copy(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copy(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(copy(item)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Object[] array) {
            ArrayList<Object> copy = new ArrayList<>(array.length);
            for (Object item : array) {
                copy.add(copy(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
