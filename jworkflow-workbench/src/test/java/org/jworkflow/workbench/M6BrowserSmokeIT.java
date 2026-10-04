package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Real Chrome and Edge /build and /test journeys; the fake sandbox stands in for the OS mechanism proven separately. */
class M6BrowserSmokeIT {
    static FakeSandbox sandbox;
    static Map<String, String> host;

    @Configuration(proxyBeanMethods = false)
    static class FakeSandboxConfiguration {
        @Bean @Primary Sandbox fakeSandbox() { return sandbox; }
        @Bean @Primary CommandService fakeCommands(ProjectWorkspace workspace, OperationCoordinator coordinator) {
            return new CommandService(workspace, sandbox, coordinator, host, Duration.ofMinutes(10));
        }
    }

    @Test void buildAndTestJourneysInSupportedBrowsers(@TempDir Path temp) throws Exception {
        for (String browser : new String[]{"chrome", "msedge"}) run(browser, temp.resolve(browser));
    }

    private static void run(String browser, Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path dist = Files.createDirectories(home.resolve(".m2/wrapper/dists/apache-maven-3.9.11-bin/h/apache-maven-3.9.11"));
        Files.createDirectories(dist.resolve("bin")); Files.writeString(dist.resolve("bin/m2.conf"), "");
        Files.createDirectories(dist.resolve("boot")); Files.writeString(dist.resolve("boot/plexus-classworlds-2.9.0.jar"), "");
        Files.createDirectories(dist.resolve("lib")); Files.writeString(dist.resolve("lib/maven-core-3.9.11.jar"), "");
        Files.createDirectories(home.resolve(".m2/repository"));
        Path root = temp.resolve("M6 project 漢字"); Files.createDirectories(root.resolve("src/main/java/sample"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.jworkflow</groupId><artifactId>jworkflow-core</artifactId><version>0.1.0-SNAPSHOT</version></dependency></dependencies></project>");
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://x/apache-maven-3.9.11-bin.zip\n");
        Files.writeString(root.resolve("src/main/java/sample/OrderCommand.java"), "package sample; import org.jworkflow.application.Command; public record OrderCommand(String id) implements Command {}");
        sandbox = new FakeSandbox();
        sandbox.hangWhen = command -> command.contains("verify");
        host = Map.of("USERPROFILE", home.toString(), "HOME", home.toString(), "SystemRoot", System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "OPENAI_API_KEY", "sk-must-not-reach-builds", "PATH", "");
        try (var app = SpringApplication.run(new Class<?>[]{WorkbenchApplication.class, FakeSandboxConfiguration.class}, new String[]{"--workbench.browser.enabled=false",
                "--workbench.preference-file=" + temp.resolve("last.txt"), "--spring.ai.openai.api-key="})) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort(); Path log = temp.resolve("m6-" + browser + ".log");
            var process = new ProcessBuilder("node", "scripts/m6-browser-smoke.mjs").redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("WORKBENCH_TEST_URL", "http://127.0.0.1:" + port + "/#" + app.getBean(SessionAuthority.class).bootstrapToken());
            process.environment().put("WORKBENCH_PROJECT_ROOT", root.toString()); process.environment().put("WORKBENCH_BROWSER", browser);
            Process child = process.start(); boolean finished = child.waitFor(90, TimeUnit.SECONDS); if (!finished) child.destroyForcibly();
            String output = Files.readString(log); System.out.println(output);
            assertTrue(finished, "M6 " + browser + " journey timed out"); assertEquals(0, child.exitValue(), output);
        }
        assertEquals(3, sandbox.launches.size(), "Self-test probe, one approved build and one approved test; denied commands never run");
        assertTrue(sandbox.launches.get(1).contains("package") && sandbox.launches.get(2).contains("verify"));
        assertTrue(sandbox.environments.stream().noneMatch(env -> env.containsKey("OPENAI_API_KEY")));
        assertTrue(sandbox.kills.get() >= 1, "Cancel killed the test tree");
        assertFalse(Files.readString(root.resolve(".jworkflow/verification.json")).contains("BUILD OUTPUT"), "No transcript is persisted");
    }
}
