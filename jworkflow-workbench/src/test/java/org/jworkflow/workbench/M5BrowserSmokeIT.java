package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** Real Chrome and Edge M5 assistant journeys against a local fake provider; no real AI request is made. */
class M5BrowserSmokeIT {
    static final String PROPOSAL = "{\"summary\":\"Add an archive end and route failures to it\",\"operations\":[{\"op\":\"add_node\",\"type\":\"end\",\"after\":\"done\",\"fields\":{\"NAME\":\"archived\"}},{\"op\":\"set_field\",\"node\":\"charge\",\"field\":\"FAILURE\",\"value\":\"archived\"}]}";

    @Test void assistantJourneysInSupportedBrowsers(@TempDir Path temp) throws Exception {
        for (String browser : new String[]{"chrome", "msedge"}) run(browser, temp.resolve(browser));
    }

    private static void run(String browser, Path temp) throws Exception {
        Path root = temp.resolve("M5 project 漢字"); Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.jworkflow</groupId><artifactId>jworkflow-core</artifactId><version>0.1.0-SNAPSHOT</version></dependency></dependencies></project>");
        Files.writeString(root.resolve("src/main/java/sample/OrderEvent.java"), "package sample; public record OrderEvent(String id) {} // shared-marker");
        Files.writeString(root.resolve("src/main/java/sample/OrderCommand.java"), "package sample; import org.jworkflow.application.Command; public record OrderCommand(String id) implements Command {}");
        Files.writeString(root.resolve(".env"), "SECRET=1");
        try (var provider = new FakeResponsesServer()) {
            provider.scripts.add(FakeResponsesServer.sse(FakeResponsesServer.delta("Adding "), FakeResponsesServer.delta("an archive end."), FakeResponsesServer.completed("Adding an archive end.", PROPOSAL)));
            provider.scripts.add(FakeResponsesServer.sse(FakeResponsesServer.completed("Again.", PROPOSAL)));
            provider.scripts.add(provider.hold("Thinking about it"));
            provider.scripts.add(FakeResponsesServer.sse(FakeResponsesServer.delta("Short reply."), FakeResponsesServer.completed("Short reply.", null)));
            try (var app = SpringApplication.run(WorkbenchApplication.class, "--workbench.browser.enabled=false", "--workbench.preference-file=" + temp.resolve("last.txt"),
                    "--spring.ai.openai.api-key=test-key-not-for-browser", "--spring.ai.openai.base-url=" + provider.baseUrl(), "--spring.ai.retry.max-attempts=1")) {
                int port = ((WebServerApplicationContext) app).getWebServer().getPort(); Path log = temp.resolve("m5-" + browser + ".log");
                var process = new ProcessBuilder("node", "scripts/m5-browser-smoke.mjs").redirectErrorStream(true).redirectOutput(log.toFile());
                process.environment().put("WORKBENCH_TEST_URL", "http://127.0.0.1:" + port + "/#" + app.getBean(SessionAuthority.class).bootstrapToken());
                process.environment().put("WORKBENCH_PROJECT_ROOT", root.toString()); process.environment().put("WORKBENCH_BROWSER", browser);
                Process child = process.start(); boolean finished = child.waitFor(90, TimeUnit.SECONDS); if (!finished) child.destroyForcibly();
                String output = Files.readString(log); System.out.println(output);
                assertTrue(finished, "M5 " + browser + " journey timed out"); assertEquals(0, child.exitValue(), output);
            }
            assertEquals(4, provider.bodies.size());
            String first = provider.bodies.get(0);
            assertTrue(first.contains("\"model\":\"gpt-6-astra\"") && first.contains("\"store\":false"), first);
            assertTrue(first.contains("shared-marker") && first.contains("<project-data>"), "Shared file and workflow are sent as data");
            assertFalse(first.contains("SECRET=1"), "Unshared and secret files are never sent");
            assertTrue(provider.bodies.stream().noneMatch(body -> body.contains("test-key-not-for-browser")), "The key is only a header");
            assertTrue(provider.clientGone.await(5, TimeUnit.SECONDS), "/cancel closed the provider stream");
        }
    }
}
