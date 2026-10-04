package org.jworkflow.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable state must take its time from the engine's injected clock. Fails when production sources read the wall
 * clock directly, in Java or in SQL. {@code Clock.systemUTC()} stays allowed: it is the default clock, not a read.
 */
class NoWallClockGuardTest {
    private static final List<String> JAVA_PATTERNS = List.of(
            "Instant.now(", "System.currentTimeMillis(", "LocalDateTime.now(", "OffsetDateTime.now(",
            "ZonedDateTime.now(", "LocalDate.now(");
    /** SQL time functions; matched only in .sql files and inside Java string literals. */
    private static final Pattern SQL_TIME = Pattern.compile(
            "(?i)\\bcurrent_timestamp\\b|(?<![\\w.])now\\s*\\(\\s*\\)|datetime\\s*\\(\\s*'now'|\\blocaltimestamp\\b|\\bclock_timestamp\\s*\\(");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    /** Schema initializers stamp migration history before any engine (and so any clock) exists. */
    private static final Map<String, String> ALLOWED = Map.of(
            "JdbcSchemaInitializer.java", "Instant.now(",
            "PostgresqlSchemaInitializer.java", "Instant.now(");

    @Test
    void productionSourcesUseTheInjectedClock() throws IOException {
        List<String> violations = new ArrayList<>();
        scan(Path.of("src/main/java"), violations);
        scan(Path.of("src/main/resources"), violations);
        assertTrue(violations.isEmpty(), "Wall-clock reads must use the injected Clock:\n" + String.join("\n", violations));
    }

    private static void scan(Path root, List<String> violations) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                boolean java = name.endsWith(".java");
                if (!java && !name.endsWith(".sql")) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.strip();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("--")) continue;
                    String location = file + ":" + (i + 1) + ": " + trimmed;
                    if (java) {
                        for (String pattern : JAVA_PATTERNS) {
                            if (line.contains(pattern) && !pattern.equals(ALLOWED.get(name))) violations.add(location);
                        }
                        Matcher literals = STRING_LITERAL.matcher(line);
                        while (literals.find()) {
                            if (SQL_TIME.matcher(literals.group()).find()) { violations.add(location); break; }
                        }
                    } else if (SQL_TIME.matcher(line).find()) {
                        violations.add(location);
                    }
                }
            }
        }
    }
}
