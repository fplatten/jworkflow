package org.jworkflow.workbench;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Benign escape probe executed inside the sandbox by the self-test. It touches only Workbench-created fixtures and
 * prints one {@code check=result} line per attempt; cache checks are keyed by argument index because the sandbox may
 * present paths under different drive letters. Self-contained: no other Workbench classes are loaded.
 * Arguments: outsideFile outsideDirectory rootDirectory loopbackPort cacheDirectory...
 */
public final class SandboxProbe {
    private SandboxProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("descendant")) {
            Thread.sleep(1500);
            Files.writeString(Path.of(args[1]), "descendant survived");
            return;
        }
        Path outsideFile = Path.of(args[0]), outsideDirectory = Path.of(args[1]), root = Path.of(args[2]);
        int port = Integer.parseInt(args[3]);
        report("outsideRead", attempt(() -> Files.readString(outsideFile)));
        report("outsideWrite", attempt(() -> Files.writeString(outsideDirectory.resolve("probe-write.txt"), "escape")));
        report("rootWrite", attempt(() -> Files.writeString(root.resolve("probe-root-write.txt"), "inside")));
        // Compatibility (not a security check): on JDK 20+ this queries the volume root, which a Windows sandbox may not open.
        report("javaTempFiles", attempt(() -> { if (!java.io.File.createTempFile("probe", null, root.toFile()).delete()) throw new IOException("delete failed"); }));
        for (int i = 4; i < args.length; i++) {
            Path cache = Path.of(args[i]);
            report("cacheRead:" + (i - 4), attempt(() -> { try (Stream<Path> entries = Files.list(cache)) { entries.findFirst(); } }));
            Path marker = cache.resolve(".jworkflow-probe-" + ProcessHandle.current().pid());
            String write = attempt(() -> Files.writeString(marker, "mutation"));
            if (write.equals("allowed")) Files.deleteIfExists(marker);
            report("cacheWrite:" + (i - 4), write);
        }
        // Network results are classified, so a socket layer that fails to start can never pass as isolation.
        String sockets;
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { sockets = "yes"; }
        catch (Exception unavailable) { sockets = "no (" + unavailable.getClass().getSimpleName() + ")"; }
        report("socketsAvailable", sockets);
        report("networkLoopbackOutside", connect("127.0.0.1", port));
        // 192.0.2.1 is TEST-NET-1 (RFC 5737): reserved and never routed, so no real host is contacted.
        report("networkReserved", connect("192.0.2.1", 9));
        String secrets = System.getenv().keySet().stream().filter(name -> name.toUpperCase().matches(".*(API_KEY|TOKEN|SECRET|PASSWORD|OPENAI).*")).sorted().collect(Collectors.joining(","));
        report("secretEnvironment", secrets.isEmpty() ? "none" : secrets);
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        report("descendantStarted", attempt(() -> new ProcessBuilder(java, "-XX:-UsePerfData", "-cp", System.getProperty("java.class.path"),
                SandboxProbe.class.getName(), "descendant", root.resolve("probe-descendant.txt").toString()).start()));
        System.out.flush();
    }

    /** "connected", "denied by policy" (an immediate permission error), "timeout", or "blocked (…)" for other failures. */
    private static String connect(String host, int port) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), 3000); return "connected"; }
        catch (java.net.SocketTimeoutException timeout) { return "timeout"; }
        catch (Exception failure) {
            String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("permission denied") || message.contains("not permitted") || message.contains("access")) return "denied by policy";
            if (message.contains("timed out")) return "timeout";
            return "blocked (" + failure.getClass().getSimpleName() + ")";
        }
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static String attempt(Attempt attempt) {
        try { attempt.run(); return "allowed"; }
        catch (IOException | SecurityException denied) { return "denied"; }
        catch (Exception other) { return "denied"; }
    }

    private static void report(String check, String result) { System.out.println("probe:" + check + "=" + result); }
}
