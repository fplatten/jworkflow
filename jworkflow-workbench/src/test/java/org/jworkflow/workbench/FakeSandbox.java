package org.jworkflow.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Scripted sandbox for command-flow tests. The self-test probe gets the output of a correctly confined run; builds
 * emit {@code output}, then exit with {@code exitCode} or hang until killed. Real confinement is proven separately by
 * {@link SandboxConfinementTests}.
 */
final class FakeSandbox implements Sandbox {
    volatile String output = "BUILD OUTPUT line\n";
    volatile int exitCode = 0;
    volatile boolean hang;
    /** Hang only for matching commands (for example the test template). */
    volatile java.util.function.Predicate<List<String>> hangWhen = command -> false;
    volatile boolean probeEscapes;
    volatile String javaTempFiles = "allowed";
    final List<List<String>> launches = new CopyOnWriteArrayList<>();
    final List<java.util.Map<String, String>> environments = new CopyOnWriteArrayList<>();
    final AtomicInteger kills = new AtomicInteger();

    @Override public String mechanism() { return "fake sandbox"; }
    @Override public String unavailableReason() { return ""; }
    @Override public Setup setup(Path root, List<Path> readOnly) { return new Setup(List.of(), readOnly.stream().map(p -> "grant " + p).toList(), "fake"); }
    @Override public void applyWorkbenchGrants(Path root) { }

    @Override public Running start(Launch launch, Consumer<byte[]> sink) {
        launches.add(launch.command()); environments.add(launch.environment());
        boolean probe = launch.command().contains(SandboxProbe.class.getName());
        boolean hangs = hang || hangWhen.test(launch.command());
        CountDownLatch killed = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            if (probe) {
                StringBuilder lines = new StringBuilder();
                String result = probeEscapes ? "allowed" : "denied";
                lines.append("probe:outsideRead=").append(result).append('\n').append("probe:outsideWrite=").append(result).append('\n')
                        .append("probe:rootWrite=allowed\nprobe:javaTempFiles=").append(javaTempFiles).append("\nprobe:socketsAvailable=yes\nprobe:networkLoopbackOutside=").append(probeEscapes ? "connected" : "timeout")
                        .append("\nprobe:networkReserved=").append(probeEscapes ? "timeout" : "denied by policy").append("\nprobe:secretEnvironment=none\nprobe:descendantStarted=allowed\n");
                for (int i = 0; i < launch.readOnly().size(); i++) lines.append("probe:cacheRead:").append(i).append("=allowed\nprobe:cacheWrite:").append(i).append("=denied\n");
                sink.accept(lines.toString().getBytes(StandardCharsets.UTF_8));
            } else if (!output.isEmpty()) sink.accept(output.getBytes(StandardCharsets.UTF_8));
        });
        return new Running() {
            @Override public Integer waitFor(Duration timeout) throws InterruptedException {
                if (!probe && hangs) return killed.await(timeout.toMillis(), TimeUnit.MILLISECONDS) ? 137 : null;
                Thread.sleep(50);
                return probe ? 0 : exitCode;
            }
            @Override public void killTree() { kills.incrementAndGet(); killed.countDown(); }
        };
    }
}
