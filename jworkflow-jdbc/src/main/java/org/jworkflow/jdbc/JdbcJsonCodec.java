package org.jworkflow.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jworkflow.persistence.PersistenceSerializationException;

import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, versioned JSON codec shared by all JDBC repositories. */
public final class JdbcJsonCodec {
    public static final String FORMAT = "jworkflow-json";
    public static final int VERSION = 1;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper mapper;

    public JdbcJsonCodec() {
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public String write(Object value) {
        Object safe = validateAndCopy(value, "$", true);
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", FORMAT);
        envelope.put("value", safe);
        envelope.put("version", VERSION);
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new PersistenceSerializationException("Failed to encode JDBC JSON", exception);
        }
    }

    public byte[] writeUtf8(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public Object read(String json) {
        Map<String, Object> envelope = parseEnvelope(json);
        return immutableJson(envelope.get("value"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        Object value = read(json);
        if (!(value instanceof Map<?, ?> map)) {
            throw new PersistenceSerializationException("Persisted JSON value is not an object");
        }
        return (Map<String, Object>) map;
    }

    public Map<String, Object> readPersistedMap(String persisted) {
        if (persisted == null || persisted.isBlank() || "{}".equals(persisted.trim())) return Map.of();
        String trimmed = persisted.trim();
        if (!trimmed.startsWith("{")) {
            throw legacyFormat();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(trimmed, MAP_TYPE);
            if (FORMAT.equals(parsed.get("format"))) return readMap(trimmed);
            if (looksLikeLegacyMapToString(trimmed)) throw legacyFormat();
            return castStringMap(immutableJson(parsed));
        } catch (JsonProcessingException exception) {
            throw legacyFormat();
        }
    }

    public <T> T read(String json, Class<T> type) {
        Object value = parseEnvelope(json).get("value");
        try {
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException exception) {
            throw new PersistenceSerializationException("Failed to decode JDBC JSON as " + type.getName(), exception);
        }
    }

    /** Binary payloads use BLOB columns and never pass through JSON or platform character encodings. */
    public byte[] copyBinary(byte[] payload) {
        return payload == null ? null : payload.clone();
    }

    private Map<String, Object> parseEnvelope(String json) {
        if (json == null || json.isBlank()) throw new PersistenceSerializationException("Persisted JSON is required");
        try {
            Map<String, Object> envelope = mapper.readValue(json, MAP_TYPE);
            if (!FORMAT.equals(envelope.get("format"))) {
                throw new PersistenceSerializationException("Unsupported persisted JSON format");
            }
            Object version = envelope.get("version");
            if (!(version instanceof Number number) || number.intValue() != VERSION) {
                throw new PersistenceSerializationException("Unsupported persisted JSON version: " + version);
            }
            if (!envelope.containsKey("value")) throw new PersistenceSerializationException("Persisted JSON value is missing");
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new PersistenceSerializationException("Invalid persisted JSON", exception);
        }
    }

    private Object validateAndCopy(Object value, String path, boolean allowTemporal) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) return value;
        if (allowTemporal && value instanceof TemporalAccessor) return value;
        if (value instanceof byte[]) {
            throw new PersistenceSerializationException("Binary value at " + path + " must be stored in a BLOB column");
        }
        if (value instanceof Map<?, ?> map) {
            ArrayList<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String)) throw new PersistenceSerializationException("JSON map key at " + path + " must be a string");
                keys.add((String) key);
            }
            Collections.sort(keys);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (String key : keys) result.put(key, validateAndCopy(map.get(key), path + "." + key, false));
            return result;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> result = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) result.add(validateAndCopy(list.get(index), path + "[" + index + "]", false));
            return result;
        }
        throw new PersistenceSerializationException("Unsupported persisted value at " + path + ": " + value.getClass().getName());
    }

    private Object immutableJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), immutableJson(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(immutableJson(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static boolean looksLikeLegacyMapToString(String value) {
        return value.matches(".*[A-Za-z0-9_]+\\s*=.*");
    }

    private static PersistenceSerializationException legacyFormat() {
        return new PersistenceSerializationException(
                "Legacy Map.toString() data is not losslessly recoverable; migrate it with application-specific knowledge");
    }
}
