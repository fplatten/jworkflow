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

/**
 * Deterministic, versioned JSON codec shared by all JDBC repositories.
 *
 * <p>Uses a version-1 JSON envelope and deterministic property ordering. Values are limited to supported JSON data
 * and explicitly mapped domain records, not Java object serialization. Non-empty legacy Map.toString data is
 * rejected because it cannot be decoded losslessly.</p>
 */
public final class JdbcJsonCodec {
    private static final String TEXT_FORMAT = "format";
    private static final String TEXT_VALUE = "value";
    /**
     * Stable marker identifying the persisted jworkflow JSON envelope.
     */
    public static final String FORMAT = "jworkflow-json";
    /**
     * Current persisted JSON envelope version.
     */
    public static final int VERSION = 1;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper mapper;

    /**
     * Constructs JdbcJsonCodec with its default configuration.
     */
    public JdbcJsonCodec() {
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Encodes a supported value in the deterministic versioned JSON envelope; unsupported values fail explicitly.
     * @param value the value to encode or copy
     * @return the resulting text
     */
    public String write(Object value) {
        Object safe = validateAndCopy(value, "$", true);
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(TEXT_FORMAT, FORMAT);
        envelope.put(TEXT_VALUE, safe);
        envelope.put("version", VERSION);
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new PersistenceSerializationException("Failed to encode JDBC JSON", exception);
        }
    }

    /**
     * Encodes the versioned JSON envelope as UTF-8 bytes.
     * @param value the value to encode or copy
     * @return the resulting bytes
     */
    public byte[] writeUtf8(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes versioned JSON into the requested model and rejects incompatible or lossy legacy representations.
     * @param json versioned JSON representation to decode
     * @return the resulting object
     */
    public Object read(String json) {
        Map<String, Object> envelope = parseEnvelope(json);
        return immutableJson(envelope.get(TEXT_VALUE));
    }

    /**
     * Decodes a versioned JSON object and rejects non-map values.
     * @param json versioned JSON representation to decode
     * @return the resulting key/value mapping
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        Object value = read(json);
        if (!(value instanceof Map<?, ?> map)) {
            throw new PersistenceSerializationException("Persisted JSON value is not an object");
        }
        return (Map<String, Object>) map;
    }

    /**
     * Reads versioned or supported legacy JSON maps. Null, blank and empty-object input yield an empty map; lossy
     * map-toString data is rejected.
     * @param persisted whether the observation describes durable state
     * @return the resulting key/value mapping
     */
    public Map<String, Object> readPersistedMap(String persisted) {
        if (persisted == null || persisted.isBlank() || "{}".equals(persisted.trim())) return Map.of();
        String trimmed = persisted.trim();
        if (!trimmed.startsWith("{")) {
            throw legacyFormat();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(trimmed, MAP_TYPE);
            if (FORMAT.equals(parsed.get(TEXT_FORMAT))) return readMap(trimmed);
            if (looksLikeLegacyMapToString(trimmed)) throw legacyFormat();
            return castStringMap(immutableJson(parsed));
        } catch (JsonProcessingException exception) {
            throw legacyFormat();
        }
    }

    /**
     * Decodes versioned JSON into the requested model and rejects incompatible or lossy legacy representations.
     * @param <T> the result type
     * @param json versioned JSON representation to decode
     * @param type target Java type supported by the persisted codec
     * @return the value produced by the work
     */
    public <T> T read(String json, Class<T> type) {
        Object value = parseEnvelope(json).get(TEXT_VALUE);
        try {
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException exception) {
            throw new PersistenceSerializationException("Failed to decode JDBC JSON as " + type.getName(), exception);
        }
    }

    /**
     * Binary payloads use SQLite BLOB/PostgreSQL bytea and never pass through JSON or character encodings.
     * @param payload supported event body; null denotes an absent body
     * @return the resulting bytes
     */
    public byte[] copyBinary(byte[] payload) {
        return payload == null ? null : payload.clone();
    }

    private Map<String, Object> parseEnvelope(String json) {
        if (json == null || json.isBlank()) throw new PersistenceSerializationException("Persisted JSON is required");
        try {
            Map<String, Object> envelope = mapper.readValue(json, MAP_TYPE);
            if (!FORMAT.equals(envelope.get(TEXT_FORMAT))) {
                throw new PersistenceSerializationException("Unsupported persisted JSON format");
            }
            Object version = envelope.get("version");
            if (!(version instanceof Number number) || number.intValue() != VERSION) {
                throw new PersistenceSerializationException("Unsupported persisted JSON version: " + version);
            }
            if (!envelope.containsKey(TEXT_VALUE)) throw new PersistenceSerializationException("Persisted JSON value is missing");
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new PersistenceSerializationException("Invalid persisted JSON", exception);
        }
    }

    private Object validateAndCopy(Object value, String path, boolean allowTemporal) {
        if (isJsonScalar(value)) return value;
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

    private static boolean isJsonScalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Number;
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
        for (int equalsIndex = value.indexOf('='); equalsIndex >= 0;
                equalsIndex = value.indexOf('=', equalsIndex + 1)) {
            int candidateIndex = equalsIndex - 1;
            while (candidateIndex >= 0 && Character.isWhitespace(value.charAt(candidateIndex))) {
                candidateIndex--;
            }
            if (candidateIndex >= 0 && isAsciiWordCharacter(value.charAt(candidateIndex))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiWordCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_';
    }

    private static PersistenceSerializationException legacyFormat() {
        return new PersistenceSerializationException(
                "Legacy Map.toString() data is not losslessly recoverable; migrate it with application-specific knowledge");
    }
}
