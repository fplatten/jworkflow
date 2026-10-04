import java.nio.file.*;
import java.net.*;
import java.util.concurrent.TimeUnit;

/** Negative control: demonstrates that a working directory is NOT an OS sandbox. Only touches generated fixtures. */
public class ConfinementProbe {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("descendant")) {
            Thread.sleep(500); Files.writeString(Path.of(args[1]), "benign descendant"); return;
        }
        if (args.length > 0 && args[0].equals("child")) {
            Path outside = Path.of(args[1]);
            System.out.println("outsideRead=" + Files.readString(outside.resolve("sentinel.txt")).equals("fixture-only"));
            Files.writeString(outside.resolve("write.txt"), "benign write");
            Files.writeString(outside.resolve("cache.txt"), "cache mutation");
            try (var connection = new Socket("127.0.0.1", Integer.parseInt(args[2]))) {System.out.println("loopbackNetwork=" + connection.isConnected());}
            new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"), "ConfinementProbe", "descendant", outside.resolve("descendant.txt").toString()).start();
            return;
        }
        Path fixture = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "confinement-negative-");
        Path root = Files.createDirectory(fixture.resolve("allowed-root")); Path outside = Files.createDirectory(fixture.resolve("outside-root"));
        Files.writeString(outside.resolve("sentinel.txt"), "fixture-only"); Files.writeString(outside.resolve("cache.txt"), "readonly-cache-fixture");
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var child = new ProcessBuilder(javaExecutable(), "-cp", Path.of(System.getProperty("java.class.path")).toAbsolutePath().toString(), "ConfinementProbe", "child", outside.toString(), Integer.toString(server.getLocalPort())).directory(root.toFile()).inheritIO().start();
            if (!child.waitFor(10, TimeUnit.SECONDS)) {child.destroyForcibly(); throw new IllegalStateException("Probe timed out");}
            if (child.exitValue() != 0) throw new IllegalStateException("Negative-control setup failed");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(outside.resolve("descendant.txt")) && System.nanoTime() < deadline) Thread.sleep(50);
            System.out.println("platform=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            System.out.println("outsideWrite=" + Files.exists(outside.resolve("write.txt")));
            System.out.println("cacheMutation=" + Files.readString(outside.resolve("cache.txt")).equals("cache mutation"));
            System.out.println("descendantSurvivedParent=" + Files.exists(outside.resolve("descendant.txt")));
            System.out.println("RESULT: working-directory-only design is rejected. OS confinement remains unproved; product execution stays disabled.");
        }
    }
    private static String javaExecutable() {return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();}
}
