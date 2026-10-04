package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OS-enforced confinement for target build/test processes (SEC-02/SEC-03, WB-21). A launch may write only below
 * {@code writableRoot}, read only the listed read-only paths plus the OS runtime, open no network connections, and
 * every descendant dies when the launch ends. Implementations never fall back to unconfined execution.
 */
public interface Sandbox {
    /** One confined process tree request. {@code command} is executed directly, never through a shell. */
    record Launch(List<String> command, Path workingDirectory, Map<String, String> environment, Path writableRoot, List<Path> readOnly) {}

    /** A running confined process tree. */
    interface Running {
        /** Waits for the root process; on timeout returns null without stopping it. */
        Integer waitFor(Duration timeout) throws InterruptedException;
        /** Kills the root process and every descendant; safe to call repeatedly. */
        void killTree();
    }

    /** Short platform mechanism name, for example "Windows AppContainer + Job Object". */
    String mechanism();

    /** Empty when the mechanism can be used on this machine, otherwise why not. */
    String unavailableReason();

    /** One-time setup the user must approve or perform before launches can read/write the given paths. */
    Setup setup(Path writableRoot, List<Path> readOnly) throws IOException;

    /** {@code workbenchGrants} are root-internal changes Workbench may apply after approval; {@code userCommands} are for the user to run. */
    record Setup(List<String> workbenchGrants, List<String> userCommands, String note) {}

    /** Applies the approved root-internal grants (for example the project folder ACL on Windows). */
    void applyWorkbenchGrants(Path writableRoot) throws IOException;

    /** Starts the confined tree; {@code output} receives combined stdout/stderr bytes as they arrive. */
    Running start(Launch launch, Consumer<byte[]> output) throws IOException;

    static Sandbox forCurrentPlatform() {
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Windows")) return new WindowsAppContainerSandbox();
        if (os.startsWith("Mac")) return new MacSandbox();
        return new Unsupported("Confined build/test is supported on Windows and macOS only.");
    }

    /** Explicitly unavailable confinement; execution stays disabled. */
    record Unsupported(String unavailableReason) implements Sandbox {
        @Override public String mechanism() { return "none"; }
        @Override public Setup setup(Path writableRoot, List<Path> readOnly) { return new Setup(List.of(), List.of(), unavailableReason); }
        @Override public void applyWorkbenchGrants(Path writableRoot) { throw new IllegalStateException(unavailableReason); }
        @Override public Running start(Launch launch, Consumer<byte[]> output) { throw new IllegalStateException(unavailableReason); }
    }
}
