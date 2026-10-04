package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real confined /build and /test with real Maven and Gradle inside the platform sandbox (WB-21/WB-22, AT-31). Tool
 * distributions and caches are copied into Workbench-owned {@code target/live-tools} and only those copies are made
 * readable to the sandbox, so no user folder permissions change. Opt-in because the first run copies the caches:
 * {@code WORKBENCH_LIVE_BUILD=1 mvnw -Dtest=LiveConfinedBuildIT test}.
 */
@EnabledIfEnvironmentVariable(named = "WORKBENCH_LIVE_BUILD", matches = "1")
class LiveConfinedBuildIT {
    @TempDir Path temp;
    static final Path TOOLS = Path.of("target/live-tools").toAbsolutePath();
    static final Path HOME = TOOLS.resolve("home");

    private static void copyOnce(Path source, Path target) throws IOException {
        if (Files.exists(target.resolve(".copied"))) return;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { Files.createDirectories(target.resolve(source.relativize(dir).toString())); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING); return FileVisitResult.CONTINUE; }
        });
        Files.writeString(target.resolve(".copied"), "ok");
    }

    private static Sandbox sandbox() throws IOException {
        Sandbox sandbox = Sandbox.forCurrentPlatform();
        assertEquals("", sandbox.unavailableReason());
        if (sandbox instanceof WindowsAppContainerSandbox) WindowsAppContainerSandbox.grantReadOnly(TOOLS);
        return sandbox;
    }

    private record Run(CommandService commands, BlockingQueue<CommandService.Result> results, StringBuffer output) {}

    private Run service(Path root, Sandbox sandbox) throws IOException {
        var workspace = new ProjectWorkspace(new LastProjectPreference(temp.resolve("pref.txt")));
        workspace.confirm(root.toString());
        Map<String, String> host = new HashMap<>(System.getenv());
        host.put("USERPROFILE", HOME.toString()); host.put("HOME", HOME.toString()); host.remove("GRADLE_USER_HOME"); host.remove("MAVEN_USER_HOME");
        var commands = new CommandService(workspace, sandbox, new OperationCoordinator(), host, Duration.ofMinutes(10));
        BlockingQueue<CommandService.Result> results = new LinkedBlockingQueue<>(); StringBuffer output = new StringBuffer();
        commands.listener(new CommandService.Listener() { public void output(String text) { output.append(text); } public void finished(CommandService.Result r) { results.add(r); } });
        return new Run(commands, results, output);
    }

    private static CommandService.Result run(Run run, BuildTools.Kind kind) throws Exception {
        run.output().setLength(0);
        var proposal = run.commands().propose(kind);
        Files.write(Path.of("target", "live-" + kind.name().toLowerCase(Locale.ROOT) + ".args"), proposal.command());
        Files.writeString(Path.of("target", "live-" + kind.name().toLowerCase(Locale.ROOT) + ".cwd"), proposal.workingDirectory());
        run.commands().approve(proposal.id());
        CommandService.Result result = run.results().poll(10, TimeUnit.MINUTES);
        assertNotNull(result);
        Files.writeString(Path.of("target", "live-" + kind.name().toLowerCase(Locale.ROOT) + ".log"), run.output().toString());
        System.out.println("---- " + kind + " " + result + "\n" + tail(run.output().toString()));
        return result;
    }

    private static String tail(String text) { return text.length() > 6000 ? text.substring(text.length() - 6000) : text; }

    private static void sources(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("src/main/java/demo/App.java"), "package demo;\npublic final class App { public static int answer() { return 42; } }\n");
        Files.createDirectories(root.resolve("src/test/java/demo"));
        Files.writeString(root.resolve("src/test/java/demo/AppTest.java"), "package demo;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "class AppTest { @Test void unit() { assertEquals(42, App.answer()); } }\n");
        Files.writeString(root.resolve("src/test/java/demo/AppIT.java"), "package demo;\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "class AppIT { @Test void integration() { assertEquals(42, App.answer()); } }\n");
    }

    @Test void realMavenBuildAndTestRunConfinedOffline() throws Exception {
        Path dist = Files.list(Path.of(System.getProperty("user.home"), ".m2/wrapper/dists/apache-maven-3.9.11-bin")).findFirst().orElseThrow();
        copyOnce(dist, HOME.resolve(".m2/wrapper/dists/apache-maven-3.9.11-bin/live"));
        copyOnce(Path.of(System.getProperty("user.home"), ".m2/repository"), HOME.resolve(".m2/repository"));
        Sandbox sandbox = sandbox();
        Path root = Files.createDirectories(temp.resolve("live maven 漢字"));
        sources(root);
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip\n");
        if (System.getenv("WORKBENCH_LIVE_DEBUG") != null) Files.writeString(root.resolve(".mvn/jvm.config"), "-Xlog:exceptions=info");
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>live</artifactId><version>1</version>
                  <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.0.3</version><scope>test</scope></dependency></dependencies>
                  <build><plugins>
                    <plugin><artifactId>maven-clean-plugin</artifactId><version>3.5.0</version></plugin>
                    <plugin><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
                    <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                    <plugin><artifactId>maven-jar-plugin</artifactId><version>3.5.0</version></plugin>
                    <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
                    <plugin><artifactId>maven-failsafe-plugin</artifactId><version>3.5.6</version>
                      <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions></plugin>
                  </plugins></build>
                </project>
                """);
        var run = service(root, sandbox);
        var setup = run.commands().verify(true);
        System.out.println("Self-test: " + setup.checks());
        assertTrue(setup.verified(), setup.failures().toString());

        var build = run(run, BuildTools.Kind.BUILD);
        assertEquals("passed", build.status(), build.message());
        assertTrue(Files.exists(root.resolve("target/live-1.jar")));
        assertFalse(Files.exists(root.resolve("target/surefire-reports")), "/build runs no unit tests");
        assertFalse(Files.exists(root.resolve("target/failsafe-reports")), "/build runs no integration tests");
        assertFalse(Files.exists(root.resolve("target/test-classes")), "/build does not even compile tests");

        var test = run(run, BuildTools.Kind.TEST);
        assertEquals("passed", test.status(), test.message());
        assertTrue(Files.exists(root.resolve("target/surefire-reports/TEST-demo.AppTest.xml")), "/test runs unit tests");
        assertTrue(Files.exists(root.resolve("target/failsafe-reports/TEST-demo.AppIT.xml")), "/test runs configured integration tests");
        assertFalse(Files.exists(HOME.resolve(".m2/repository/demo")), "Nothing is installed into the read-only cache");
    }

    @Test void realGradleBuildAndTestRunConfinedOffline() throws Exception {
        if (SandboxSelfTest.isWindows()) {
            // MVP scope: Maven only on Windows. The refusal happens before any self-test or launch.
            Path root = Files.createDirectories(temp.resolve("gradle on windows"));
            Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
            var run = service(root, sandbox());
            assertEquals(CommandService.GRADLE_ON_WINDOWS, run.commands().setup().unavailable());
            assertThrows(IllegalStateException.class, () -> run.commands().propose(BuildTools.Kind.BUILD));
            return;
        }
        Path gradleHome = Path.of("C:/devtools/gradle-8.5");
        assertTrue(Files.isDirectory(gradleHome), "Expected Gradle 8.5 at " + gradleHome);
        copyOnce(gradleHome, HOME.resolve(".gradle/wrapper/dists/gradle-8.5-bin/live/gradle-8.5"));
        copyOnce(Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2"), HOME.resolve(".gradle/caches/modules-2"));
        Sandbox sandbox = sandbox();
        Path root = Files.createDirectories(temp.resolve("live gradle 漢字"));
        sources(root);
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n");
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'live'\n");
        if (System.getenv("WORKBENCH_LIVE_DEBUG") != null) Files.writeString(root.resolve("gradle.properties"), "org.gradle.logging.stacktrace=all\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'java' }
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                repositories { mavenCentral() }
                dependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'; testRuntimeOnly 'org.junit.platform:junit-platform-launcher' }
                tasks.named('test') { useJUnitPlatform(); exclude '**/*IT.class' }
                tasks.register('integrationTest', Test) { useJUnitPlatform(); include '**/*IT.class'; testClassesDirs = sourceSets.test.output.classesDirs; classpath = sourceSets.test.runtimeClasspath }
                tasks.named('check') { dependsOn 'integrationTest' }
                """);
        var run = service(root, sandbox);
        var setup = run.commands().verify(true);
        if (!"allowed".equals(setup.checks().get("javaTempFiles"))) {
            // Known Windows blocker: Gradle's startup temp-file probe needs the volume root, which the sandbox cannot open.
            assertFalse(setup.verified());
            assertTrue(setup.failures().stream().anyMatch(f -> f.contains("Gradle cannot run in this sandbox")), setup.failures().toString());
            assertThrows(IllegalStateException.class, () -> run.commands().propose(BuildTools.Kind.BUILD));
            System.out.println("Gradle blocked as designed: " + setup.failures());
            return;
        }
        assertTrue(setup.verified(), setup.failures().toString());
        var build = run(run, BuildTools.Kind.BUILD);
        assertEquals("passed", build.status(), build.message());
        assertTrue(Files.exists(root.resolve("build/libs/live.jar")));
        assertFalse(Files.exists(root.resolve("build/test-results")), "/build runs no tests");
        var test = run(run, BuildTools.Kind.TEST);
        assertEquals("passed", test.status(), test.message());
        assertTrue(Files.exists(root.resolve("build/test-results/test/TEST-demo.AppTest.xml")));
        assertTrue(Files.exists(root.resolve("build/test-results/integrationTest/TEST-demo.AppIT.xml")));
    }
}
