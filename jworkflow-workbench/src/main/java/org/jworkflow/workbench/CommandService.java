package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * /build and /test (OPS-02–OPS-04, WB-22): confinement is proven by a self-test for the confirmed project before any
 * launch; every execution and retry needs approval of the exact command; launches never start automatically, never
 * retry and never download. Output lives only in a bounded in-memory buffer; only the outcome and source identity are
 * recorded for the plan.
 */
public final class CommandService implements AutoCloseable {
    static final int OUTPUT_LIMIT = 1024 * 1024;
    /** Owner decision (October 3, 2026): on Windows the MVP supports Maven only for /build and /test. */
    static final String GRADLE_ON_WINDOWS = "Gradle /build and /test are not supported on Windows in this MVP; Maven projects are supported. "
            + "Build and test this Gradle project yourself outside Workbench. (Inside the Windows sandbox Gradle cannot start: on JDK 20+ "
            + "java.io.File.createTempFile needs access to the drive root, which the sandbox is not given.)";

    public record Proposal(String id, String kind, String tool, String toolVersion, List<String> command, String workingDirectory,
            Map<String, String> environment, List<String> readOnly, String writable, String deadline, String note, String mechanism) {}
    public record Setup(String mechanism, String unavailable, boolean verified, String buildSystem, List<String> workbenchGrants, List<String> userCommands,
            List<String> remediation, List<String> failures, Map<String, String> checks, String note) {}
    public record Result(String kind, String status, Integer exitCode, long durationMillis, long outputChars, boolean truncated, String message) {}
    /** AI-05: an exact, redacted tail of the last run's output, previewed before the user approves sharing it. */
    public record Excerpt(String id, String command, String status, String text, int redactions) {}
    static final int EXCERPT_LIMIT = 4000;
    private static final java.util.regex.Pattern[] SECRETS = {
            java.util.regex.Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"),
            java.util.regex.Pattern.compile("(?i)(authorization:\\s*(bearer|basic)\\s+)\\S+"),
            java.util.regex.Pattern.compile("(?i)((?:password|passwd|secret|token|api[_-]?key|access[_-]?key)[\\w.-]*\\s*[=:]\\s*)\\S+"),
            java.util.regex.Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}|\\bAKIA[0-9A-Z]{16}\\b|\\bgh[pousr]_[A-Za-z0-9]{30,}")};

    /** Receives live output and the final result; the terminal implements it. */
    public interface Listener { void output(String text); void finished(Result result); }

    private final ProjectWorkspace workspace;
    private final Sandbox sandbox;
    private final OperationCoordinator coordinator;
    private final Map<String, String> host;
    private final Duration deadline;
    private final boolean windows;
    private volatile Listener listener = new Listener() { public void output(String text) {} public void finished(Result result) {} };

    private Path currentRoot;
    private Path verifiedRoot;
    private Setup lastSetup;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private Sandbox.Running running;
    private boolean cancelled;
    private String lastKind = "", lastStatus = "";
    private Excerpt excerpt;
    private final StringBuilder buffer = new StringBuilder();
    private long dropped;

    private record Pending(Proposal proposal, BuildTools.Plan plan, Path root, BuildTools.Kind kind) {}

    public CommandService(ProjectWorkspace workspace, Sandbox sandbox, OperationCoordinator coordinator, Map<String, String> host, Duration deadline) {
        this(workspace, sandbox, coordinator, host, deadline, SandboxSelfTest.isWindows());
    }

    CommandService(ProjectWorkspace workspace, Sandbox sandbox, OperationCoordinator coordinator, Map<String, String> host, Duration deadline, boolean windows) {
        this.workspace = workspace; this.sandbox = sandbox; this.coordinator = coordinator; this.host = Map.copyOf(host); this.deadline = deadline; this.windows = windows;
    }

    /** Why build/test cannot run for this project here, or empty: the platform mechanism first, then MVP scope. */
    private String unavailable(String buildSystem) {
        String reason = sandbox.unavailableReason();
        if (!reason.isEmpty()) return reason;
        return windows && buildSystem.startsWith("Gradle") ? GRADLE_ON_WINDOWS : "";
    }

    public void listener(Listener listener) { this.listener = listener; }

    // ---- Confinement setup and self-test (WB-21) ----

    public synchronized Setup setup() throws IOException {
        Path root = root();
        String buildSystem = workspace.profile().buildSystem();
        String unavailable = unavailable(buildSystem);
        if (unavailable.equals(GRADLE_ON_WINDOWS))
            return lastSetup = new Setup(sandbox.mechanism(), unavailable, false, buildSystem, List.of(), List.of(), List.of(), List.of(), Map.of(), "");
        BuildTools.Plan plan;
        try { plan = BuildTools.plan(BuildTools.Kind.BUILD, root, buildSystem, BuildTools.javaHome(host), host); }
        catch (BuildTools.Missing missing) {
            return lastSetup = new Setup(sandbox.mechanism(), unavailable, false, buildSystem, List.of(), List.of(), numbered(missing.getMessage(), missing.remediation), List.of(), Map.of(), "");
        }
        Sandbox.Setup grants = unavailable.isEmpty() ? sandbox.setup(root, plan.readOnly()) : new Sandbox.Setup(List.of(), List.of(), unavailable);
        // Earlier self-test findings for this root (failures and the read-only grants still missing) stay visible.
        return lastSetup = new Setup(sandbox.mechanism(), unavailable, root.equals(verifiedRoot), buildSystem, grants.workbenchGrants(),
                lastSetup == null ? List.of() : lastSetup.userCommands(), List.of(), lastSetup == null ? List.of() : lastSetup.failures(),
                lastSetup == null ? Map.of() : lastSetup.checks(), grants.note());
    }

    /** Applies the approved project-folder grant and proves confinement with the escape probe. */
    public Setup verify(boolean grantProjectFolder) throws IOException, InterruptedException {
        Path root; BuildTools.Plan plan; String buildSystem;
        synchronized (this) {
            if (coordinator.active() != null) throw new IllegalStateException("An operation is active. Wait for it to finish or use /cancel.");
            root = root(); buildSystem = workspace.profile().buildSystem();
            String unavailable = unavailable(buildSystem);
            if (!unavailable.isEmpty()) throw new IllegalStateException(unavailable);
            try { plan = BuildTools.plan(BuildTools.Kind.BUILD, root, buildSystem, BuildTools.javaHome(host), host); }
            catch (BuildTools.Missing missing) { throw new IllegalArgumentException(String.join("\n", numbered(missing.getMessage(), missing.remediation))); }
        }
        if (grantProjectFolder) sandbox.applyWorkbenchGrants(root);
        SandboxSelfTest.Result result = SandboxSelfTest.run(sandbox, root, BuildTools.javaHome(host), plan.readOnly(), plan.environment());
        List<String> failures = new ArrayList<>(result.failures());
        // Gradle probes the file system with java.io.File.createTempFile at startup; where the sandbox cannot run it, Gradle cannot start.
        if (buildSystem.startsWith("Gradle") && !"allowed".equals(result.checks().get("javaTempFiles")))
            failures.add("Gradle cannot run in this sandbox: java.io.File.createTempFile fails (JDK 20+ reads the volume root, for example C:\\, which the Windows sandbox may not open"
                    + " and only an administrator could allow). /build and /test stay disabled for this Gradle project; Maven projects are not affected.");
        boolean passed = failures.isEmpty();
        synchronized (this) {
            verifiedRoot = passed ? root : null;
            Sandbox.Setup grants = sandbox.setup(root, result.unreadable());
            lastSetup = new Setup(sandbox.mechanism(), "", passed, buildSystem, grants.workbenchGrants(), grants.userCommands(), List.of(),
                    List.copyOf(failures), result.checks(), grants.note());
            return lastSetup;
        }
    }

    // ---- Proposals and execution ----

    /** The exact command for one execution; approval is required every time. */
    public synchronized Proposal propose(BuildTools.Kind kind) throws IOException {
        Path root = root();
        String unavailable = unavailable(workspace.profile().buildSystem());
        if (!unavailable.isEmpty()) throw new IllegalStateException(unavailable.equals(GRADLE_ON_WINDOWS) ? unavailable : "Build and test are disabled: " + unavailable);
        if (!root.equals(verifiedRoot)) throw new IllegalStateException("Build and test stay disabled until the confinement self-test passes for this project. Run it in the Build and test panel.");
        if (coordinator.active() != null) throw new IllegalStateException("An operation is active; nothing was queued.");
        BuildTools.Plan plan;
        try { plan = BuildTools.plan(kind, root, workspace.profile().buildSystem(), BuildTools.javaHome(host), host); }
        catch (BuildTools.Missing missing) { throw new IllegalArgumentException(String.join("\n", numbered(missing.getMessage(), missing.remediation))); }
        pending.clear();
        Proposal proposal = new Proposal(UUID.randomUUID().toString(), kind == BuildTools.Kind.BUILD ? "build" : "test", plan.tool(), plan.toolVersion(), plan.command(),
                root.toString(), plan.environment(), plan.readOnly().stream().map(Path::toString).toList(), root.toString(), deadline.toMinutes() + " minutes from launch",
                plan.note(), sandbox.mechanism());
        pending.put(proposal.id(), new Pending(proposal, plan, root, kind));
        return proposal;
    }

    public synchronized void deny(String id) { pending.remove(id); }

    /** Starts the approved proposal; refuses stale, reused or concurrent approvals. */
    public Result approve(String id) throws IOException {
        Pending approved; OperationCoordinator.Ticket ticket; Sandbox.Running process; String fingerprint;
        synchronized (this) {
            approved = pending.remove(id);
            if (approved == null) throw new IllegalStateException("That command was already used, denied or replaced. Run /build or /test again to review a new one.");
            Path root = root();
            BuildTools.Plan current;
            try { current = BuildTools.plan(approved.kind(), root, workspace.profile().buildSystem(), BuildTools.javaHome(host), host); }
            catch (BuildTools.Missing missing) { throw new IllegalArgumentException(missing.getMessage()); }
            if (!root.equals(approved.root()) || !root.equals(verifiedRoot) || !current.command().equals(approved.plan().command()) || !current.environment().equals(approved.plan().environment()))
                throw new IllegalStateException("The project, its tools or confinement changed since you reviewed the command. Run the command again to review it.");
            ticket = coordinator.tryBegin(approved.kind() == BuildTools.Kind.BUILD ? OperationCoordinator.Kind.BUILD : OperationCoordinator.Kind.TEST, this::cancel);
            if (ticket == null) throw new IllegalStateException("Another operation is active; nothing was queued.");
            buffer.setLength(0); dropped = 0; cancelled = false; excerpt = null;
            fingerprint = workspace.verificationFingerprint();
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                process = sandbox.start(new Sandbox.Launch(approved.plan().command(), root, approved.plan().environment(), root, approved.plan().readOnly()), bytes -> {
                    String text;
                    synchronized (decoder) {
                        CharBuffer chars = CharBuffer.allocate(bytes.length + 8);
                        decoder.decode(ByteBuffer.wrap(bytes), chars, false); chars.flip(); text = chars.toString();
                    }
                    capture(text);
                    listener.output(text);
                });
            } catch (IOException | RuntimeException failure) { coordinator.end(ticket); throw failure; }
            running = process;
        }
        long started = System.nanoTime();
        Pending launched = approved; OperationCoordinator.Ticket held = ticket; Sandbox.Running tree = process;
        Thread.ofVirtual().name("command-supervisor").start(() -> supervise(launched, held, tree, started, fingerprint));
        return new Result(launched.proposal().kind(), "running", null, 0, 0, false, "Started " + launched.proposal().tool() + " " + String.join(" ", launched.plan().command().subList(Math.max(0, launched.plan().command().size() - 2), launched.plan().command().size())) + ".");
    }

    private void supervise(Pending launched, OperationCoordinator.Ticket ticket, Sandbox.Running tree, long started, String fingerprint) {
        Integer exit = null; String status;
        try { exit = tree.waitFor(deadline); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        boolean wasCancelled;
        synchronized (this) { wasCancelled = cancelled; }
        if (exit == null) tree.killTree();
        status = wasCancelled ? "cancelled" : exit == null ? "timeout" : exit == 0 ? "passed" : "failed";
        long duration = (System.nanoTime() - started) / 1_000_000;
        Result result;
        synchronized (this) {
            running = null; lastKind = launched.proposal().kind(); lastStatus = status;
            result = new Result(launched.proposal().kind(), status, wasCancelled ? null : exit, duration, buffer.length() + dropped, dropped > 0, message(launched, status, exit, duration));
        }
        try { workspace.recordVerification(launched.proposal().kind(), status, exit, fingerprint); } catch (IOException | RuntimeException ignored) { /* The outcome is still shown. */ }
        coordinator.end(ticket);
        listener.finished(result);
    }

    private String message(Pending launched, String status, Integer exit, long millis) {
        String what = "/" + launched.proposal().kind();
        String seconds = String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        String truncation = dropped > 0 ? " Output beyond " + OUTPUT_LIMIT / 1024 + " KiB was dropped from memory (" + dropped + " characters)." : "";
        return switch (status) {
            case "passed" -> what + " passed in " + seconds + "." + truncation;
            case "failed" -> what + " failed with exit code " + exit + " after " + seconds + ". No retry was started." + offlineHint() + truncation;
            case "timeout" -> what + " exceeded the " + deadline.toMinutes() + "-minute limit; the process tree was stopped." + truncation;
            default -> what + " was cancelled; the process tree was stopped. Sources and the conversation are unchanged." + truncation;
        };
    }

    private synchronized String offlineHint() {
        String text = buffer.toString();
        return text.contains("offline") && (text.contains("Cannot access") || text.contains("No cached version") || text.contains("could not be resolved"))
                ? " A dependency is missing from the read-only cache; run the build once yourself outside Workbench (with network access) to fill it, then retry here." : "";
    }

    /** /cancel during build/test: stops the process tree only. */
    public void cancel() {
        Sandbox.Running tree;
        synchronized (this) { tree = running; cancelled = true; }
        if (tree != null) tree.killTree();
    }

    /** The redacted tail of the last finished run's output for preview; sharing needs a separate approval. */
    public synchronized Excerpt excerpt() {
        if (running != null) throw new IllegalStateException("Wait for the running command to finish before sharing its output.");
        if (buffer.isEmpty() || lastKind.isEmpty()) throw new IllegalStateException("Run /build or /test first; there is no output to share.");
        String tail = buffer.length() > EXCERPT_LIMIT ? buffer.substring(buffer.length() - EXCERPT_LIMIT) : buffer.toString();
        int[] count = {0};
        for (var pattern : SECRETS) tail = pattern.matcher(tail).replaceAll(match -> { count[0]++; return java.util.regex.Matcher.quoteReplacement(match.groupCount() > 0 && match.group(1) != null && !match.group(0).startsWith("-----") ? match.group(1) + "[REDACTED]" : "[REDACTED]"); });
        excerpt = new Excerpt(UUID.randomUUID().toString(), lastKind, lastStatus, tail, count[0]);
        return excerpt;
    }

    /** Releases the previewed excerpt once; the client only names it, so it cannot substitute different text. */
    public synchronized String takeExcerpt(String id) {
        if (excerpt == null || !excerpt.id().equals(id)) throw new IllegalStateException("That excerpt is no longer available. Preview it again.");
        String text = excerpt.text(); excerpt = null;
        return text;
    }

    public synchronized String output() { return (dropped > 0 ? "[" + dropped + " earlier characters dropped]\n" : "") + buffer; }

    private synchronized void capture(String text) {
        buffer.append(text);
        int excess = buffer.length() - OUTPUT_LIMIT;
        if (excess > 0) { buffer.delete(0, excess); dropped += excess; }
    }

    private Path root() {
        Path root = workspace.rootPath();
        if (root == null) throw new IllegalStateException("Confirm a project first.");
        if (!root.equals(currentRoot)) { currentRoot = root; verifiedRoot = null; lastSetup = null; pending.clear(); }
        return root;
    }

    private static List<String> numbered(String message, List<String> steps) {
        List<String> lines = new ArrayList<>(List.of(message));
        for (int i = 0; i < steps.size(); i++) lines.add((i + 1) + ". " + steps.get(i));
        return lines;
    }

    @Override public void close() { cancel(); }
}
