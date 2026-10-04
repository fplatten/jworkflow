package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** AT-10 browser timings with a saved 500-block workflow: startup, open, edit p95 and Save, on Chrome and Edge. */
class PerformanceBrowserIT {
    @Test void fiveHundredBlockTimingsInSupportedBrowsers(@TempDir Path temp) throws Exception {
        for (String browser : new String[]{"chrome", "msedge"}) run(browser, temp.resolve(browser));
    }

    private static void run(String browser, Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("perf project"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        Files.writeString(Files.createDirectories(root.resolve("src/main/java/demo")).resolve("DoThing.java"),
                "package demo; import org.jworkflow.application.Command; public record DoThing(String id) implements Command {}");
        var mapper = WorkflowGenerationService.workflowJson();
        var document = mapper.createObjectNode();
        document.put("schemaVersion", 1); document.put("documentId", "perf"); document.put("revision", 1); document.put("workbenchVersion", "0.1.0-SNAPSHOT");
        document.put("coreProfile", "0.1.0-SNAPSHOT"); document.put("name", "Scale"); document.put("engineId", "scale"); document.put("version", "1");
        document.put("javaPackage", "demo"); document.put("outputDirectory", "src/main/resources/workflows");
        document.set("blockly", mapper.readTree(ScaleTests.fiveHundredBlocks()));
        Files.write(Files.createDirectories(root.resolve(".jworkflow")).resolve("workflow.jworkflow.json"), mapper.writeValueAsBytes(document));
        long started = System.nanoTime();
        try (var app = SpringApplication.run(WorkbenchApplication.class, "--workbench.browser.enabled=false", "--workbench.preference-file=" + temp.resolve("last.txt"), "--spring.ai.openai.api-key=")) {
            long startupMillis = (System.nanoTime() - started) / 1_000_000;
            Files.writeString(Path.of("target", "performance-startup-" + browser + ".txt"), "Backend ready (in-process Spring start) " + startupMillis + " ms (budget 10000 ms)\n");
            int port = ((WebServerApplicationContext) app).getWebServer().getPort(); Path log = temp.resolve("perf.log");
            var process = new ProcessBuilder("node", "scripts/perf-browser.mjs").redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("WORKBENCH_TEST_URL", "http://127.0.0.1:" + port + "/#" + app.getBean(SessionAuthority.class).bootstrapToken());
            process.environment().put("WORKBENCH_PROJECT_ROOT", root.toString()); process.environment().put("WORKBENCH_BROWSER", browser);
            Process child = process.start(); boolean finished = child.waitFor(150, TimeUnit.SECONDS); if (!finished) child.destroyForcibly();
            String output = Files.readString(log); System.out.println(output + "Startup " + startupMillis + " ms");
            assertTrue(finished, "Performance journey timed out"); assertEquals(0, child.exitValue(), output);
            assertTrue(startupMillis < 10_000, "Startup " + startupMillis + " ms");
        }
    }
}
