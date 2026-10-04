package org.jworkflow.workbench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * macOS confinement with {@code sandbox-exec} and a deny-by-default profile: reads limited to the OS runtime, the
 * read-only tool/cache paths and the project; writes only inside the project; all network denied; process execution
 * limited to those locations; Mach lookups limited to a few system services so no job can be submitted outside the
 * sandbox. The tree runs in its own process group (set by {@code perl setpgrp}) and the whole group is killed when
 * the launch ends. File metadata (existence, size, timestamps) remains visible outside the project; contents do not.
 */
final class MacSandbox implements Sandbox {
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec"), PERL = Path.of("/usr/bin/perl");
    private static final List<String> SYSTEM_READ = List.of("/System", "/usr", "/bin", "/sbin", "/Library/Java", "/Library/Preferences",
            "/private/etc", "/private/var/db/timezone", "/private/var/db/dyld", "/dev", "/opt/homebrew/Cellar", "/opt/homebrew/opt", "/usr/local/Cellar", "/usr/local/opt");
    private static final List<String> MACH_SERVICES = List.of("com.apple.system.opendirectoryd.libinfo", "com.apple.system.notification_center",
            "com.apple.system.logger", "com.apple.logd", "com.apple.diagnosticd", "com.apple.coreservices.launchservicesd");

    @Override public String mechanism() { return "macOS sandbox-exec + process group"; }

    @Override public String unavailableReason() {
        if (!Files.isExecutable(SANDBOX_EXEC)) return "macOS sandbox-exec is not available at " + SANDBOX_EXEC + ".";
        if (!Files.isExecutable(PERL)) return "/usr/bin/perl is needed to give the sandboxed build its own process group.";
        return "";
    }

    @Override public Setup setup(Path writableRoot, List<Path> readOnly) {
        return new Setup(List.of(), List.of(), "macOS applies the sandbox profile per launch; no permanent permission change is needed.");
    }

    @Override public void applyWorkbenchGrants(Path writableRoot) { /* Nothing persistent on macOS. */ }

    static String profile(Path writableRoot, List<Path> readOnly) {
        StringBuilder sb = new StringBuilder("(version 1)\n(deny default)\n");
        sb.append("(allow process-fork)\n(allow signal (target same-sandbox))\n(allow sysctl-read)\n(allow ipc-posix-shm)\n(allow ipc-posix-sem)\n(allow file-read-metadata)\n");
        sb.append("(allow mach-lookup");
        for (String service : MACH_SERVICES) sb.append(" (global-name ").append(literal(service)).append(')');
        sb.append(")\n(deny network*)\n");
        List<String> readable = new ArrayList<>(SYSTEM_READ);
        for (Path path : readOnly) readable.add(real(path));
        readable.add(real(writableRoot));
        sb.append("(allow file-read* (literal \"/\")");
        for (String path : readable) sb.append(" (subpath ").append(literal(path)).append(')');
        sb.append(")\n(allow process-exec");
        for (String path : readable) sb.append(" (subpath ").append(literal(path)).append(')');
        sb.append(")\n(allow file-write* (subpath ").append(literal(real(writableRoot))).append(") (literal \"/dev/null\") (literal \"/dev/tty\") (literal \"/dev/dtracehelper\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/null\") (literal \"/dev/dtracehelper\"))\n");
        return sb.toString();
    }

    @Override public Running start(Launch launch, Consumer<byte[]> output) throws IOException {
        String reason = unavailableReason();
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
        List<String> command = new ArrayList<>(List.of(PERL.toString(), "-e", "setpgrp(0,0); exec @ARGV or die \"exec failed: $!\"",
                SANDBOX_EXEC.toString(), "-p", profile(launch.writableRoot(), launch.readOnly())));
        command.addAll(launch.command());
        ProcessBuilder builder = new ProcessBuilder(command).directory(launch.workingDirectory().toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(launch.environment());
        Process process = builder.start();
        process.getOutputStream().close();
        long group = process.pid();
        Thread reader = Thread.ofPlatform().daemon().name("sandbox-output").start(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) > 0) output.accept(Arrays.copyOf(buffer, count));
            } catch (IOException closed) { /* The tree was killed. */ }
        });
        return new Running() {
            private boolean closed;
            @Override public Integer waitFor(Duration timeout) throws InterruptedException {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return null;
                killTree(); reader.join(5000);
                return process.exitValue();
            }
            @Override public synchronized void killTree() {
                if (closed) return;
                closed = true;
                // The negative PID addresses the whole process group, including descendants that outlived the root.
                try { new ProcessBuilder("/bin/kill", "-KILL", "-" + group).start().waitFor(5, TimeUnit.SECONDS); }
                catch (IOException | InterruptedException failure) { if (failure instanceof InterruptedException) Thread.currentThread().interrupt(); }
                process.destroyForcibly();
            }
        };
    }

    private static String real(Path path) {
        try { return path.toRealPath().toString(); } catch (IOException missing) { return path.toAbsolutePath().normalize().toString(); }
    }

    private static String literal(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("Unsupported character in sandbox path: " + value);
        return "\"" + value + "\"";
    }
}
