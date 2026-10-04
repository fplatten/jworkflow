package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * WB-21 escape probes on the real OS mechanism. Fixtures are Workbench-owned temporary folders; no user folder's
 * permissions are changed. The macOS variant runs on macOS runners and machines.
 */
class SandboxConfinementTests {
    @TempDir Path temp;

    /** The exact environment builds receive, so the probes test what product launches get. */
    private Map<String, String> environment(Path root) {
        try { return BuildTools.environment(root, Path.of(System.getProperty("java.home")), System.getenv(), Map.of()); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void buildEnvironmentKeepsSystemRootWhateverItsCaseAndDropsSecrets() throws Exception {
        Path root = Files.createDirectories(temp.resolve("env"));
        var env = BuildTools.environment(root, Path.of(System.getProperty("java.home")),
                Map.of("SYSTEMROOT", "C:\\Windows", "OPENAI_API_KEY", "sk-x", "AWS_SECRET_ACCESS_KEY", "y"), Map.of());
        assertEquals("C:\\Windows", env.get("SystemRoot"), "Winsock cannot start without SystemRoot");
        assertFalse(env.containsKey("OPENAI_API_KEY")); assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsAppContainerDeniesEveryEscapeAndKillsDescendants() throws Exception {
        var sandbox = new WindowsAppContainerSandbox();
        assertEquals("", sandbox.unavailableReason());
        Path root = Files.createDirectories(temp.resolve("project root 漢字"));
        Path cache = Files.createDirectories(temp.resolve("read-only cache"));
        Files.writeString(cache.resolve("artifact.jar"), "cached");
        sandbox.applyWorkbenchGrants(root);
        WindowsAppContainerSandbox.grantReadOnly(cache);
        var result = SandboxSelfTest.run(sandbox, root, Path.of(System.getProperty("java.home")), List.of(cache), environment(root));
        System.out.println("Windows self-test: " + result.checks());
        assertTrue(result.passed(), result.failures() + " " + result.checks());
        assertEquals("cached", Files.readString(cache.resolve("artifact.jar")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void ungrantedReadOnlyPathIsReportedWithTheCommandToRun() throws Exception {
        var sandbox = new WindowsAppContainerSandbox();
        Path root = Files.createDirectories(temp.resolve("root"));
        Path cache = Files.createDirectories(temp.resolve("ungranted cache"));
        sandbox.applyWorkbenchGrants(root);
        var result = SandboxSelfTest.run(sandbox, root, Path.of(System.getProperty("java.home")), List.of(cache), environment(root));
        assertFalse(result.passed());
        assertEquals(List.of(cache), result.unreadable());
        assertTrue(sandbox.setup(root, List.of(cache)).userCommands().get(0).startsWith("icacls \"" + cache + "\" /grant \"*S-1-15-2-"));
    }

    @Test @EnabledOnOs(OS.MAC)
    void macSandboxDeniesEveryEscapeAndKillsDescendants() throws Exception {
        var sandbox = new MacSandbox();
        assertEquals("", sandbox.unavailableReason());
        Path root = Files.createDirectories(temp.resolve("project root 漢字"));
        Path cache = Files.createDirectories(temp.resolve("read-only cache"));
        Files.writeString(cache.resolve("artifact.jar"), "cached");
        var result = SandboxSelfTest.run(sandbox, root, Path.of(System.getProperty("java.home")), List.of(cache), environment(root));
        System.out.println("macOS self-test: " + result.checks());
        assertTrue(result.passed(), result.failures() + " " + result.checks());
    }

    /** Negative control: with no confinement, the same probe must report the escapes, so a pass is meaningful. */
    @Test void unconfinedLaunchFailsTheSelfTest() throws Exception {
        Sandbox unconfined = new Sandbox() {
            public String mechanism() { return "none (negative control)"; }
            public String unavailableReason() { return ""; }
            public Setup setup(Path root, List<Path> readOnly) { return new Setup(List.of(), List.of(), ""); }
            public void applyWorkbenchGrants(Path root) { }
            public Running start(Launch launch, java.util.function.Consumer<byte[]> output) throws java.io.IOException {
                var builder = new ProcessBuilder(launch.command()).directory(launch.workingDirectory().toFile()).redirectErrorStream(true);
                builder.environment().clear(); builder.environment().putAll(launch.environment());
                Process process = builder.start();
                Thread.ofVirtual().start(() -> { try { output.accept(process.getInputStream().readAllBytes()); } catch (java.io.IOException ignored) { } });
                return new Running() {
                    public Integer waitFor(java.time.Duration timeout) throws InterruptedException { return process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) ? process.exitValue() : null; }
                    public void killTree() { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
                };
            }
        };
        Path root = Files.createDirectories(temp.resolve("unconfined"));
        Path cache = Files.createDirectories(temp.resolve("cache"));
        var result = SandboxSelfTest.run(unconfined, root, Path.of(System.getProperty("java.home")), List.of(cache), environment(root));
        assertFalse(result.passed());
        assertEquals("allowed", result.checks().get("outsideRead"));
        assertEquals("connected", result.checks().get("networkLoopbackOutside"));
        assertEquals("allowed", result.checks().get("cacheWrite:" + cache));
        assertTrue(result.failures().stream().anyMatch(f -> f.startsWith("networkLoopbackOutside")), result.failures().toString());
    }

    @Test void driveRemappingRewritesOnlyWholePathPrefixes() {
        var drives = new java.util.LinkedHashMap<String, String>();
        drives.put("C:\\p\\root", "Z:"); drives.put("C:\\tools\\jdk", "Y:"); drives.put("C:\\tools\\jdk-17", "X:");
        assertEquals("Z:\\", WindowsAppContainerSandbox.remap("C:\\p\\root", drives));
        assertEquals("Z:\\.jworkflow\\tmp", WindowsAppContainerSandbox.remap("C:\\p\\root\\.jworkflow\\tmp", drives));
        assertEquals("-Dclassworlds.conf=Y:\\bin\\m2.conf", WindowsAppContainerSandbox.remap("-Dclassworlds.conf=C:\\tools\\jdk\\bin\\m2.conf", drives));
        assertEquals("Y:\\bin;C:\\Windows\\System32", WindowsAppContainerSandbox.remap("C:\\tools\\jdk\\bin;C:\\Windows\\System32", drives));
        assertEquals("X:\\bin", WindowsAppContainerSandbox.remap("C:\\tools\\jdk-17\\bin", drives), "A longer sibling folder is not mistaken for a prefix");
        assertEquals("C:\\p\\rootless", WindowsAppContainerSandbox.remap("C:\\p\\rootless", drives));
        assertEquals("prefixC:\\p\\root", WindowsAppContainerSandbox.remap("prefixC:\\p\\root", drives));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void driveMappingsAreRemovedAfterEveryLaunch() throws Exception {
        var sandbox = new WindowsAppContainerSandbox();
        Path root = Files.createDirectories(temp.resolve("mapped root"));
        sandbox.applyWorkbenchGrants(root);
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var finished = sandbox.start(new Sandbox.Launch(List.of(javaExe, "-XX:-UsePerfData", "-version"), root, environment(root), root, List.of(Path.of(System.getProperty("java.home")))), b -> { });
        assertEquals(0, finished.waitFor(java.time.Duration.ofSeconds(60)));
        var killed = sandbox.start(new Sandbox.Launch(List.of(javaExe, "-XX:-UsePerfData", "-version"), root, environment(root), root, List.of()), b -> { });
        killed.killTree();
        for (char letter = 'H'; letter <= 'Z'; letter++) {
            Process query = new ProcessBuilder("subst").redirectErrorStream(true).start();
            String mappings = new String(query.getInputStream().readAllBytes()); query.waitFor();
            assertFalse(mappings.contains(root.getFileName().toString()), "Leaked mapping: " + mappings);
            assertFalse(mappings.contains("jdk"), "Leaked JDK mapping: " + mappings);
        }
    }

    @Test void windowsCommandLineQuotingRoundTrips() {
        // Backslashes are literal unless they precede a quote; a quoted argument doubles trailing backslashes.
        assertEquals("a \"b c\" \"\" \"d\\\"e\" f\\\\ g\\h \"x y\\\\\"", WindowsAppContainerSandbox.commandLine(List.of("a", "b c", "", "d\"e", "f\\\\", "g\\h", "x y\\")));
    }
}
