package org.jworkflow.workbench;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Proves confinement on this machine before any target build runs (SEC-03). The probe runs inside the sandbox with
 * the same read-only paths a build gets; execution is enabled only when every escape is denied, the read-only paths
 * are readable but not writable, the project is writable, no secret reaches the environment and a detached
 * descendant does not survive.
 */
final class SandboxSelfTest {
    record Result(boolean passed, Map<String, String> checks, List<Path> unreadable, List<String> failures) {}

    private SandboxSelfTest() {}

    static Result run(Sandbox sandbox, Path root, Path javaHome, List<Path> readOnly, Map<String, String> environment) throws IOException, InterruptedException {
        Path probeDirectory = root.resolve(".jworkflow/sandbox-probe");
        Path classes = Files.createDirectories(probeDirectory.resolve("org/jworkflow/workbench"));
        for (String name : List.of("SandboxProbe.class", "SandboxProbe$Attempt.class")) {
            try (InputStream in = SandboxProbe.class.getResourceAsStream(name)) {
                if (in == null) throw new IOException(name + " is not packaged.");
                Files.write(classes.resolve(name), in.readAllBytes());
            }
        }
        Files.deleteIfExists(root.resolve("probe-root-write.txt"));
        Files.deleteIfExists(root.resolve("probe-descendant.txt"));
        Path outside = Files.createTempDirectory("jworkflow-sandbox-outside-");
        List<Path> external = new ArrayList<>(readOnly); external.add(javaHome); external.add(outside);
        if (!nonPortable(external).isEmpty()) {
            Files.deleteIfExists(outside);
            return new Result(false, Map.of(), List.of(), List.of("These paths contain characters the Windows java launcher cannot pass as arguments: " + nonPortable(external)
                    + ". Move the JDK, build tool or cache to a path without them, or set TEMP to such a path."));
        }
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "outside-only");
        Map<String, String> checks = new LinkedHashMap<>();
        // The executor is declared first so the server socket closes first and the accept loop can end.
        try (var accepts = Executors.newVirtualThreadPerTaskExecutor(); ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            accepts.submit(() -> { while (!server.isClosed()) server.accept().close(); return null; });
            // Project paths are relative to the working directory: the Windows java launcher passes arguments through the ANSI code page.
            List<String> command = new ArrayList<>(List.of(javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java").toString(), "-XX:-UsePerfData",
                    "-cp", ".jworkflow/sandbox-probe", SandboxProbe.class.getName(), sentinel.toString(), outside.toString(), ".", Integer.toString(server.getLocalPort())));
            for (Path path : readOnly) command.add(path.toString());
            StringBuilder output = new StringBuilder();
            Sandbox.Running running = sandbox.start(new Sandbox.Launch(command, root, environment, root, readOnly),
                    bytes -> { synchronized (output) { output.append(new String(bytes, StandardCharsets.UTF_8)); } });
            Integer exit = running.waitFor(Duration.ofSeconds(60));
            if (exit == null) { running.killTree(); checks.put("completed", "timeout"); }
            else checks.put("completed", "exit " + exit);
            String text; synchronized (output) { text = output.toString(); }
            text.lines().filter(line -> line.startsWith("probe:")).forEach(line -> {
                int equals = line.indexOf('=');
                if (equals > 6) checks.put(line.substring(6, equals), line.substring(equals + 1).strip());
            });
            if (!text.contains("probe:")) checks.put("output", text.length() > 2000 ? text.substring(0, 2000) : text);
            Thread.sleep(3000);
            checks.put("descendantSurvived", Files.exists(root.resolve("probe-descendant.txt")) ? "yes" : "no");
            checks.put("outsideWriteObserved", Files.exists(outside.resolve("probe-write.txt")) ? "yes" : "no");
        } finally {
            try (var files = Files.walk(outside)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
            Files.deleteIfExists(root.resolve("probe-root-write.txt"));
            Files.deleteIfExists(root.resolve("probe-descendant.txt"));
        }
        List<String> failures = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        expect(checks, "completed", "exit 0", failures);
        expect(checks, "outsideRead", "denied", failures);
        expect(checks, "outsideWrite", "denied", failures);
        expect(checks, "outsideWriteObserved", "no", failures);
        expect(checks, "rootWrite", "allowed", failures);
        // Windows builds need working sockets (Gradle talks to its own workers over loopback), so a socket layer that
        // fails to start is a failure, not a pass. macOS denies all networking, so sockets may be unavailable there.
        if (isWindows()) expect(checks, "socketsAvailable", "yes", failures);
        String loopback = checks.getOrDefault("networkLoopbackOutside", "missing");
        if (loopback.equals("connected") || loopback.equals("missing")) failures.add("networkLoopbackOutside was " + loopback + " (a loopback server outside the sandbox must not be reachable)");
        expect(checks, "networkReserved", "denied by policy", failures);
        expect(checks, "secretEnvironment", "none", failures);
        expect(checks, "descendantStarted", "allowed", failures);
        expect(checks, "descendantSurvived", "no", failures);
        for (int i = 0; i < readOnly.size(); i++) {
            Path path = readOnly.get(i);
            String read = checks.remove("cacheRead:" + i), write = checks.remove("cacheWrite:" + i);
            checks.put("cacheRead:" + path, read == null ? "missing" : read);
            checks.put("cacheWrite:" + path, write == null ? "missing" : write);
            if (!"allowed".equals(read)) unreadable.add(path);
            expect(checks, "cacheWrite:" + path, "denied", failures);
        }
        if (!unreadable.isEmpty()) failures.add("The sandbox cannot read: " + unreadable);
        return new Result(failures.isEmpty(), checks, unreadable, failures);
    }

    private static void expect(Map<String, String> checks, String key, String value, List<String> failures) {
        String actual = checks.getOrDefault(key, "missing");
        if (!actual.equals(value)) failures.add(key + " was " + actual + " (expected " + value + ")");
    }

    /** External paths a Windows build argument cannot carry (characters outside the ANSI code page), or empty. */
    static List<Path> nonPortable(List<Path> paths) {
        if (!isWindows()) return List.of();
        var encoder = java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")).newEncoder();
        return paths.stream().filter(path -> !encoder.canEncode(path.toString())).toList();
    }

    static boolean isWindows() { return System.getProperty("os.name", "").startsWith("Windows"); }
}
