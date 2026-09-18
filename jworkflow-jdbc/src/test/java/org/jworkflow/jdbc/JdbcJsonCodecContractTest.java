package org.jworkflow.jdbc;

import org.jworkflow.persistence.PersistenceSerializationException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JdbcJsonCodecContractTest {
    public static void main(String[] args) {
        JdbcJsonCodec codec = new JdbcJsonCodec();
        nestedValuesRoundTrip(codec);
        outputIsDeterministic(codec);
        utf8IsExplicit(codec);
        javaTimeUsesStableEncoding(codec);
        binaryPolicyIsExplicit(codec);
        unsupportedValuesAreRejected(codec);
        legacyValuesAreHandledHonestly(codec);
    }

    private static void nestedValuesRoundTrip(JdbcJsonCodec codec) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("null", null);
        input.put("emptyMap", Map.of());
        input.put("emptyList", List.of());
        input.put("values", Arrays.asList("text", true, 7, 9L, new BigInteger("12345678901234567890"),
                new BigDecimal("12.50"), null, Map.of("nested", "yes")));
        Map<String, Object> result = codec.readMap(codec.write(input));
        check(result.containsKey("null") && result.get("null") == null, "null must round trip");
        check(((List<?>) result.get("values")).size() == 8, "nested list must round trip");
        check(((Map<?, ?>) result.get("emptyMap")).isEmpty() && ((List<?>) result.get("emptyList")).isEmpty(),
                "empty collections must round trip");
        expect(UnsupportedOperationException.class, () -> result.put("mutation", true));
    }

    private static void outputIsDeterministic(JdbcJsonCodec codec) {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1);
        first.put("a", Map.of("y", 2, "b", 3));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("b", 3, "y", 2));
        second.put("z", 1);
        check(codec.write(first).equals(codec.write(second)), "map insertion order must not affect persisted JSON");
    }

    private static void utf8IsExplicit(JdbcJsonCodec codec) {
        String value = "Zażółć gęślą jaźń — 東京";
        byte[] bytes = codec.writeUtf8(Map.of("message", value));
        check(codec.readMap(new String(bytes, StandardCharsets.UTF_8)).get("message").equals(value),
                "UTF-8 content must round trip");
    }

    private static void javaTimeUsesStableEncoding(JdbcJsonCodec codec) {
        Instant instant = Instant.parse("2026-09-17T12:34:56.123456Z");
        String encoded = codec.write(instant);
        check(encoded.contains("2026-09-17T12:34:56.123456Z"), "timestamps must use ISO-8601 text");
        check(codec.read(encoded, Instant.class).equals(instant), "Java time must round trip");
    }

    private static void binaryPolicyIsExplicit(JdbcJsonCodec codec) {
        byte[] original = new byte[]{0, 1, -1};
        byte[] copy = codec.copyBinary(original);
        original[0] = 9;
        check(copy[0] == 0, "binary BLOB values must be defensively copied");
        expect(PersistenceSerializationException.class, () -> codec.write(copy));
    }

    private static void unsupportedValuesAreRejected(JdbcJsonCodec codec) {
        expect(PersistenceSerializationException.class, () -> codec.write(new Object()));
        expect(PersistenceSerializationException.class, () -> codec.write(Map.of(7, "not a string key")));
    }

    private static void legacyValuesAreHandledHonestly(JdbcJsonCodec codec) {
        check(codec.readPersistedMap("{}").isEmpty(), "legacy empty map is unambiguous");
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("{customer=Ada, amount=12.50}"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("a =value"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("A=value"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("0=value"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("_=value"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("-=value"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("="));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("plain text"));
        check("wrong".equals(codec.readPersistedMap(
                "{\"format\":\"wrong\",\"version\":1,\"value\":{}}").get("format")),
                "ordinary legacy JSON objects must remain readable");
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("{\"format\":\"jworkflow-json\",\"version\":2,\"value\":{}}"));
        expect(PersistenceSerializationException.class, () -> codec.readPersistedMap("{\"format\":\"jworkflow-json\",\"version\":1}"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        try {
            work.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw failure;
        }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
