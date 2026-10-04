package org.jworkflow.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WB-21–WB-23 command flow: self-test gating, exact approval, deadlines, cancellation, locks and bounded output. */
class CommandServiceTests {
    @TempDir Path temp;

    /** A Maven project with a cached wrapper distribution and a Gradle project with an installed Gradle. */
    record Fixture(Path root, Path home, Map<String, String> host, ProjectWorkspace workspace, FakeSandbox sandbox, OperationCoordinator coordinator,
                   CommandService commands, BlockingQueue<CommandService.Result> results, StringBuffer output) {}

    private Fixture maven(String mavenVersion, Duration deadline) throws IOException {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path dist = Files.createDirectories(home.resolve(".m2/wrapper/dists/apache-maven-" + mavenVersion + "-bin/abc123/apache-maven-" + mavenVersion));
        Files.createDirectories(dist.resolve("bin")); Files.writeString(dist.resolve("bin/m2.conf"), "main is x");
        Files.createDirectories(dist.resolve("boot")); Files.writeString(dist.resolve("boot/plexus-classworlds-2.9.0.jar"), "");
        Files.createDirectories(dist.resolve("lib")); Files.writeString(dist.resolve("lib/maven-core-" + mavenVersion + ".jar"), "");
        Files.createDirectories(home.resolve(".m2/repository"));
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><servers><server><password>secret</password></server></servers></settings>");
        Path root = Files.createDirectories(temp.resolve("maven project"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/" + mavenVersion + "/apache-maven-" + mavenVersion + "-bin.zip\n");
        return fixture(root, home, deadline);
    }

    private boolean windows;

    private Fixture fixture(Path root, Path home, Duration deadline) throws IOException {
        Map<String, String> host = new HashMap<>(Map.of("USERPROFILE", home.toString(), "HOME", home.toString(), "SystemRoot", "C:\\Windows",
                "OPENAI_API_KEY", "sk-must-not-leak", "GITHUB_TOKEN", "ghp-must-not-leak", "PATH", ""));
        var workspace = new ProjectWorkspace(new LastProjectPreference(temp.resolve("pref/last.txt")));
        workspace.confirm(root.toString());
        var coordinator = new OperationCoordinator();
        workspace.blockSourceWritesWhen(coordinator::sourceWritesBlocked);
        var sandbox = new FakeSandbox();
        var commands = new CommandService(workspace, sandbox, coordinator, host, deadline, windows);
        BlockingQueue<CommandService.Result> results = new LinkedBlockingQueue<>(); StringBuffer output = new StringBuffer();
        commands.listener(new CommandService.Listener() {
            public void output(String text) { output.append(text); }
            public void finished(CommandService.Result result) { results.add(result); }
        });
        return new Fixture(workspace.rootPath(), home, host, workspace, sandbox, coordinator, commands, results, output);
    }

    @Test void executionStaysDisabledUntilTheSelfTestPassesAndAnEscapingSandboxFailsIt() throws Exception {
        var f = maven("3.9.11", Duration.ofMinutes(10));
        assertThat(assertThrows(IllegalStateException.class, () -> f.commands().propose(BuildTools.Kind.BUILD)).getMessage()).contains("self-test");
        f.sandbox().probeEscapes = true;
        var failed = f.commands().verify(false);
        assertFalse(failed.verified());
        assertThat(failed.failures()).anySatisfy(line -> assertThat(line).contains("outsideRead was allowed"));
        assertThrows(IllegalStateException.class, () -> f.commands().propose(BuildTools.Kind.BUILD));
        f.sandbox().probeEscapes = false;
        assertTrue(f.commands().verify(false).verified());
        assertNotNull(f.commands().propose(BuildTools.Kind.BUILD));
    }

    @Test void templatesAreExactOfflineAndShowNoSecrets() throws Exception {
        var f = maven("3.9.11", Duration.ofMinutes(10));
        f.commands().verify(false);
        var build = f.commands().propose(BuildTools.Kind.BUILD);
        assertThat(build.command()).contains("--offline", "--batch-mode", "-Dmaven.test.skip=true", "package", "--settings", ".jworkflow/m2/settings.xml",
                "-Dmaven.repo.local=.jworkflow/m2/repository", "-Dmaven.repo.local.tail=" + f.home().resolve(".m2/repository")).doesNotContain("verify");
        assertEquals("org.codehaus.plexus.classworlds.launcher.Launcher", build.command().get(build.command().indexOf("-Dmaven.multiModuleProjectDirectory=.") + 1));
        assertEquals("<settings/>\n", Files.readString(f.root().resolve(".jworkflow/m2/settings.xml")), "User settings (credentials) never reach the sandbox");
        assertThat(build.environment()).doesNotContainKeys("OPENAI_API_KEY", "GITHUB_TOKEN").containsKey("JAVA_HOME");
        assertThat(build.environment().get(SandboxSelfTest.isWindows() ? "TEMP" : "TMPDIR")).startsWith(f.root().resolve(".jworkflow/tmp").toString());
        var test = f.commands().propose(BuildTools.Kind.TEST);
        assertThat(test.command()).contains("verify").doesNotContain("-Dmaven.test.skip=true", "package");
        assertThat(test.command()).noneMatch(arg -> arg.startsWith("-Dtest=") || arg.startsWith("-Dit.test="));
    }

    @Test void missingPrerequisitesExplainNumberedStepsWithoutDownloading() throws Exception {
        var old = maven("3.8.6", Duration.ofMinutes(10));
        var setup = old.commands().setup();
        assertThat(setup.remediation().get(0)).contains("Maven 3.8.6").contains("3.9");
        assertThat(setup.remediation().get(1)).startsWith("1. ");
        Files.writeString(old.root().resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://x/apache-maven-3.9.99-bin.zip\n");
        assertThat(old.commands().setup().remediation()).anySatisfy(line -> assertThat(line).contains("-v` yourself (outside Workbench)").contains("3.9.99"));
    }

    @Test void approvalIsExactOneShotAndRunsConfined() throws Exception {
        var f = maven("3.9.11", Duration.ofMinutes(10)); f.commands().verify(false);
        int probeLaunches = f.sandbox().launches.size();
        var denied = f.commands().propose(BuildTools.Kind.BUILD);
        f.commands().deny(denied.id());
        assertThrows(IllegalStateException.class, () -> f.commands().approve(denied.id()));
        assertEquals(probeLaunches, f.sandbox().launches.size(), "Denied commands never run");
        var proposal = f.commands().propose(BuildTools.Kind.BUILD);
        // Changing the tool configuration after review invalidates the approval.
        Files.writeString(f.root().resolve(".mvn/jvm.config"), "-Xmx256m");
        assertThat(assertThrows(IllegalStateException.class, () -> f.commands().approve(proposal.id())).getMessage()).contains("changed");
        var fresh = f.commands().propose(BuildTools.Kind.BUILD);
        f.commands().approve(fresh.id());
        assertThrows(IllegalStateException.class, () -> f.commands().approve(fresh.id()), "One approval, one execution");
        var result = f.results().poll(5, TimeUnit.SECONDS);
        assertEquals("passed", result.status()); assertEquals(0, result.exitCode());
        assertEquals(fresh.command(), f.sandbox().launches.get(f.sandbox().launches.size() - 1));
        assertThat(f.output().toString()).contains("BUILD OUTPUT line");
        assertNull(f.coordinator().active());
        String record = Files.readString(f.root().resolve(".jworkflow/verification.json"));
        assertThat(record).contains("\"status\" : \"passed\"").doesNotContain("BUILD OUTPUT");
    }

    @Test void failureIsReportedWithoutRetry() throws Exception {
        var f = maven("3.9.11", Duration.ofMinutes(10)); f.commands().verify(false);
        f.sandbox().exitCode = 1; f.sandbox().output = "[ERROR] offline mode: Cannot access central in offline mode\n";
        int before = f.sandbox().launches.size();
        f.commands().approve(f.commands().propose(BuildTools.Kind.TEST).id());
        var result = f.results().poll(5, TimeUnit.SECONDS);
        assertEquals("failed", result.status());
        assertThat(result.message()).contains("exit code 1").contains("No retry").contains("read-only cache");
        Thread.sleep(200); assertEquals(before + 1, f.sandbox().launches.size());
    }

    @Test void deadlineAndCancelStopTheTreeAndReleaseTheSlot() throws Exception {
        var f = maven("3.9.11", Duration.ofMillis(400)); f.commands().verify(false);
        f.sandbox().hang = true;
        f.commands().approve(f.commands().propose(BuildTools.Kind.TEST).id());
        assertEquals(OperationCoordinator.Kind.TEST, f.coordinator().active());
        var timeout = f.results().poll(5, TimeUnit.SECONDS);
        assertEquals("timeout", timeout.status()); assertTrue(f.sandbox().kills.get() >= 1); assertNull(f.coordinator().active());

        var g = maven2(); g.commands().verify(false); g.sandbox().hang = true;
        g.commands().approve(g.commands().propose(BuildTools.Kind.BUILD).id());
        // Source apply/revert is locked while it runs; a second operation is rejected, not queued.
        assertThrows(ProjectWorkspace.Conflict.class, () -> g.workspace().applyRevert("x"));
        assertNull(g.coordinator().tryBegin(OperationCoordinator.Kind.AI, () -> { }));
        assertThrows(IllegalStateException.class, () -> g.commands().propose(BuildTools.Kind.TEST));
        assertEquals(OperationCoordinator.Kind.BUILD, g.coordinator().cancelActive());
        var cancelled = g.results().poll(5, TimeUnit.SECONDS);
        assertEquals("cancelled", cancelled.status()); assertThat(cancelled.message()).contains("process tree was stopped");
        assertNull(g.coordinator().active());
        assertThat(Files.readString(g.root().resolve(".jworkflow/verification.json"))).contains("cancelled");
    }

    private Fixture maven2() throws IOException {
        Path other = Files.createDirectories(temp.resolve("second"));
        Path saved = temp; temp = other;
        try { return maven("3.9.11", Duration.ofMinutes(10)); } finally { temp = saved; }
    }

    @Test void outputIsBoundedInMemoryWithVisibleTruncation() throws Exception {
        var f = maven("3.9.11", Duration.ofMinutes(10)); f.commands().verify(false);
        f.sandbox().output = "x".repeat(CommandService.OUTPUT_LIMIT + 5000);
        f.commands().approve(f.commands().propose(BuildTools.Kind.BUILD).id());
        var result = f.results().poll(5, TimeUnit.SECONDS);
        assertTrue(result.truncated()); assertThat(result.message()).contains("dropped from memory");
        assertThat(f.commands().output()).startsWith("[5000 earlier characters dropped]");
    }

    @Test void gradleUsesProjectLocalHomeAndReadOnlyCache() throws Exception {
        Path home = Files.createDirectories(temp.resolve("ghome"));
        Path gradle = Files.createDirectories(home.resolve(".gradle/wrapper/dists/gradle-8.5-bin/xyz/gradle-8.5/lib"));
        Files.writeString(gradle.resolve("gradle-launcher-8.5.jar"), "");
        Files.createDirectories(home.resolve(".gradle/caches/modules-2"));
        Path root = Files.createDirectories(temp.resolve("gradle project"));
        Files.writeString(root.resolve("build.gradle.kts"), "java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n");
        var f = fixture(root, home, Duration.ofMinutes(10)); f.commands().verify(false);
        var build = f.commands().propose(BuildTools.Kind.BUILD);
        assertThat(build.command()).contains("org.gradle.launcher.GradleMain", "--offline", "--no-daemon", "assemble").doesNotContain("check", "test");
        assertEquals(f.root().resolve(".jworkflow/gradle-home").toString(), build.environment().get("GRADLE_USER_HOME"));
        assertEquals(home.resolve(".gradle/caches").toString(), build.environment().get("GRADLE_RO_DEP_CACHE"));
        assertThat(f.commands().propose(BuildTools.Kind.TEST).command()).contains("check");
        // Where the sandbox cannot run java.io temp files, Gradle cannot start, so it stays disabled with an explanation.
        f.sandbox().javaTempFiles = "denied";
        var blocked = f.commands().verify(false);
        assertFalse(blocked.verified());
        assertThat(blocked.failures()).anySatisfy(line -> assertThat(line).contains("Gradle cannot run in this sandbox").contains("Maven projects are not affected"));
        assertThrows(IllegalStateException.class, () -> f.commands().propose(BuildTools.Kind.BUILD));
    }

    /** MVP scope (owner decision, October 3, 2026): on Windows, /build and /test support Maven only. */
    @Test void windowsMvpSupportsMavenOnly() throws Exception {
        windows = true;
        var maven = maven("3.9.11", Duration.ofMinutes(10));
        assertEquals("", maven.commands().setup().unavailable());
        assertTrue(maven.commands().verify(false).verified());
        assertNotNull(maven.commands().propose(BuildTools.Kind.BUILD));

        Path home = Files.createDirectories(temp.resolve("gradle-home-win"));
        Files.writeString(Files.createDirectories(home.resolve(".gradle/wrapper/dists/gradle-8.5-bin/x/gradle-8.5/lib")).resolve("gradle-launcher-8.5.jar"), "");
        Path root = Files.createDirectories(temp.resolve("gradle on windows"));
        Files.writeString(root.resolve("build.gradle"), "sourceCompatibility = JavaVersion.VERSION_17");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n");
        var gradle = fixture(root, home, Duration.ofMinutes(10));
        var setup = gradle.commands().setup();
        assertEquals(CommandService.GRADLE_ON_WINDOWS, setup.unavailable());
        assertFalse(setup.verified());
        assertThrows(IllegalStateException.class, () -> gradle.commands().verify(false));
        assertThat(assertThrows(IllegalStateException.class, () -> gradle.commands().propose(BuildTools.Kind.BUILD)).getMessage())
                .contains("not supported on Windows in this MVP").contains("Maven projects are supported");
        assertTrue(gradle.sandbox().launches.isEmpty(), "Nothing is launched for an unsupported combination");
        windows = false;
    }

    /** AI-05: an exact, redacted tail of the last output, released once by ID only. */
    @Test void outputExcerptIsRedactedPreviewedAndReleasedOnce() throws Exception {
        var f = maven("3.9.11", Duration.ofMinutes(10)); f.commands().verify(false);
        assertThrows(IllegalStateException.class, () -> f.commands().excerpt(), "No output before a run");
        f.sandbox().exitCode = 1;
        f.sandbox().output = "[ERROR] Tests failed\nAuthorization: Bearer abcdef123456\npassword=hunter2 db.token: tok_123\nkey sk-abcdefghijklmnopqrstuvwx1234\n"
                + "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAK\n-----END RSA PRIVATE KEY-----\n" + "x".repeat(CommandService.EXCERPT_LIMIT);
        f.commands().approve(f.commands().propose(BuildTools.Kind.TEST).id());
        assertEquals("failed", f.results().poll(5, TimeUnit.SECONDS).status());
        var whole = f.commands().excerpt();
        assertEquals(CommandService.EXCERPT_LIMIT, whole.text().length(), "Only the tail is offered");
        f.sandbox().output = "[ERROR] Tests failed\nAuthorization: Bearer abcdef123456\npassword=hunter2 db.token: tok_123\nkey sk-abcdefghijklmnopqrstuvwx1234\n"
                + "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAK\n-----END RSA PRIVATE KEY-----\n";
        f.commands().approve(f.commands().propose(BuildTools.Kind.TEST).id());
        f.results().poll(5, TimeUnit.SECONDS);
        var excerpt = f.commands().excerpt();
        assertEquals("test", excerpt.command()); assertEquals("failed", excerpt.status());
        assertThat(excerpt.text()).contains("[ERROR] Tests failed", "Authorization: Bearer [REDACTED]", "password=[REDACTED]", "db.token: [REDACTED]")
                .doesNotContain("abcdef123456", "hunter2", "tok_123", "sk-abcdefghij", "MIIBOgIBAAJBAK");
        assertTrue(excerpt.redactions() >= 5);
        assertThrows(IllegalStateException.class, () -> f.commands().takeExcerpt("forged-id"));
        assertEquals(excerpt.text(), f.commands().takeExcerpt(excerpt.id()));
        assertThrows(IllegalStateException.class, () -> f.commands().takeExcerpt(excerpt.id()), "Released once");
    }

    @Test void coordinatorRejectsConcurrencyAndIgnoresStaleTickets() {
        var coordinator = new OperationCoordinator(); List<String> cancelled = new ArrayList<>();
        var first = coordinator.tryBegin(OperationCoordinator.Kind.AI, () -> cancelled.add("ai"));
        assertNull(coordinator.tryBegin(OperationCoordinator.Kind.BUILD, () -> { }));
        assertFalse(coordinator.sourceWritesBlocked());
        assertEquals(OperationCoordinator.Kind.AI, coordinator.cancelActive()); assertEquals(List.of("ai"), cancelled);
        assertTrue(coordinator.end(first)); assertFalse(coordinator.end(first), "A late completion cannot release the slot twice");
        var build = coordinator.tryBegin(OperationCoordinator.Kind.BUILD, () -> { });
        assertTrue(coordinator.sourceWritesBlocked());
        assertFalse(coordinator.end(first)); assertEquals(OperationCoordinator.Kind.BUILD, coordinator.active());
        assertTrue(coordinator.end(build)); assertNull(coordinator.cancelActive());
    }
}
