package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** Explicit browser suite: mvn -Dtest=BrowserSmokeIT test; requires npm ci and installed browser. */
class BrowserSmokeIT {
    @Test void browserRoundTripAndShutdown(@TempDir Path temp) throws Exception {
        try (var app = SpringApplication.run(WorkbenchApplication.class, "--workbench.browser.enabled=false", "--workbench.preference-file=" + temp.resolve("missing"), "--spring.ai.openai.api-key=")) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            Path log = Path.of("target", "browser-smoke-" + System.getenv().getOrDefault("WORKBENCH_BROWSER", "chrome") + ".log");
            var process = new ProcessBuilder("node", "scripts/browser-smoke.mjs").redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("WORKBENCH_TEST_URL", "http://127.0.0.1:" + port + "/#" + app.getBean(SessionAuthority.class).bootstrapToken());
            Process child = process.start();
            boolean finished = child.waitFor(45, TimeUnit.SECONDS);
            if (!finished) child.destroyForcibly();
            System.out.println(java.nio.file.Files.readString(log));
            assertTrue(finished, "Browser verification timed out"); assertEquals(0, child.exitValue());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (app.isActive() && System.nanoTime() < deadline) Thread.sleep(50);
            assertFalse(app.isActive(), "Exact /exit must stop the backend");
        }
    }
}
