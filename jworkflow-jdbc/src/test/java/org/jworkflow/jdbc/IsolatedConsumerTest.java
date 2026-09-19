package org.jworkflow.jdbc;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Forks with an explicit allowlist, not the Maven test classpath. */
class IsolatedConsumerTest {
    @Test void coreOnlyConsumerHasNoJdbcDependencies() throws Exception { run("core", Map.of()); }
    @Test void missingDriversProduceActionableSafeErrors() throws Exception { run("missing", Map.of()); }

    static void run(String mode, Map<String,String> environment) throws Exception {
        LinkedHashSet<String> classpath = new LinkedHashSet<>();
        add(classpath, "org.jworkflow.jdbc.IsolatedConsumer");
        add(classpath, "org.jworkflow.engine.WorkflowEngine");
        add(classpath, "groovy.lang.GroovyObject");
        if (!"core".equals(mode)) {
            add(classpath, "org.jworkflow.jdbc.JdbcWorkflowEngine");
            add(classpath, "com.fasterxml.jackson.databind.ObjectMapper");
            add(classpath, "com.fasterxml.jackson.core.JsonFactory");
            add(classpath, "com.fasterxml.jackson.annotation.JsonProperty");
            add(classpath, "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        }
        if ("postgres".equals(mode)) add(classpath, "org.postgresql.Driver");
        Path output = Files.createTempFile("jworkflow-isolated-", ".log");
        Process child = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp", String.join(File.pathSeparator, classpath), IsolatedConsumer.class.getName(), mode);
            builder.environment().putAll(environment);
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            child = builder.start();
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "isolated consumer timed out");
            String log = Files.readString(output);
            assertEquals(0, child.exitValue(), log);
            assertTrue(log.contains("ISOLATED_CONSUMER_OK " + mode), log);
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    private static void add(LinkedHashSet<String> entries, String className) throws Exception {
        entries.add(Path.of(Class.forName(className).getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
}
