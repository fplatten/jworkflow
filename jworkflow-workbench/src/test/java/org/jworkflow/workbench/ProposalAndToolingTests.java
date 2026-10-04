package org.jworkflow.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AT-25 proposal rejection matrix, build-tool resolution, conversation storage edges and sandbox helpers. */
class ProposalAndToolingTests {
    @TempDir Path temp;
    private static final Set<String> COMMANDS = Set.of("Charge", "demo.Charge");
    private static final Map<String, String> NODES = Map.of("charge", "step", "route", "branch", "done", "end");

    private static String reason(String arguments) {
        return assertThrows(AssistantFailure.class, () -> WorkflowEditProposals.validate(arguments, COMMANDS, NODES, "f")).getMessage();
    }
    private static String ops(String... operations) { return "{\"summary\":\"s\",\"operations\":[" + String.join(",", operations) + "]}"; }

    @Test void everyMalformedProposalIsRejectedWhole() {
        assertThat(reason("[]")).contains("not an object");
        assertThat(reason("{\"operations\":[]}")).contains("summary is required");
        assertThat(reason("{\"summary\":5,\"operations\":[]}")).contains("summary must be a string");
        assertThat(reason("{\"summary\":\"" + "x".repeat(501) + "\",\"operations\":[]}")).contains("exceeds 500");
        assertThat(reason("{\"summary\":\"a\\u0001b\",\"operations\":[{}]}")).contains("control characters");
        assertThat(reason("{\"summary\":\"s\",\"operations\":{}}")).contains("1 to 20");
        assertThat(reason(ops(Collections.nCopies(21, "{\"op\":\"remove_node\",\"node\":\"done\"}").toArray(String[]::new)))).contains("1 to 20");
        assertThat(reason(ops("5"))).contains("not an object");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"loop\",\"fields\":{}}"))).contains("type step, branch or end");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"end\",\"after\":\"ghost\",\"fields\":{\"NAME\":\"x\"}}"))).contains("unknown node 'ghost'");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"end\",\"fields\":[]}"))).contains("fields object");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"end\",\"fields\":{\"ACTION\":\"Charge\"}}"))).contains("not valid for a end");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"end\",\"fields\":{\"NAME\":7}}"))).contains("must be a string");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"end\",\"fields\":{}}"))).contains("needs a NAME");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"end\",\"fields\":{\"NAME\":\"done\"}}"))).contains("already exists");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"step\",\"fields\":{\"NAME\":\"x\",\"ACTION\":\"Refund\"}}"))).contains("cannot invent actions");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"branch\",\"fields\":{\"NAME\":\"x\",\"OPERATOR\":\"like\"}}"))).contains("operator 'like'");
        assertThat(reason(ops("{\"op\":\"add_node\",\"type\":\"branch\",\"fields\":{\"NAME\":\"x\",\"VALUE_TYPE\":\"DATE\"}}"))).contains("value type 'DATE'");
        assertThat(reason(ops("{\"op\":\"set_field\",\"node\":\"ghost\",\"field\":\"NAME\",\"value\":\"x\"}"))).contains("unknown node 'ghost'");
        assertThat(reason(ops("{\"op\":\"set_field\",\"node\":\"done\",\"field\":\"ACTION\",\"value\":\"Charge\"}"))).contains("not valid for a end");
        assertThat(reason(ops("{\"op\":\"set_field\",\"node\":\"done\",\"field\":\"NAME\",\"value\":\"charge\"}"))).contains("already used");
        assertThat(reason(ops("{\"op\":\"set_field\",\"node\":\"charge\",\"field\":\"ACTION\",\"value\":\"Refund\"}"))).contains("cannot invent");
        assertThat(reason(ops("{\"op\":\"remove_node\",\"node\":\"ghost\"}"))).contains("unknown node");
        assertThat(reason(ops("{\"op\":\"set_start\",\"value\":\"ghost\"}"))).contains("unknown node");
        assertThat(reason(ops("{\"op\":\"run\"}"))).contains("not supported");
        assertThat(reason(ops("{\"op\":\"remove_node\"}"))).contains("node is required");
    }

    @Test void validProposalsTrackRenamesAndRemovals() {
        var proposal = WorkflowEditProposals.validate(ops(
                "{\"op\":\"set_field\",\"node\":\"done\",\"field\":\"NAME\",\"value\":\"finished\"}",
                "{\"op\":\"add_node\",\"type\":\"branch\",\"after\":\"finished\",\"fields\":{\"NAME\":\"check\",\"OPERATOR\":\"gte\",\"VALUE_TYPE\":\"NUMBER\",\"VALUE\":\"1\"}}",
                "{\"op\":\"set_field\",\"node\":\"charge\",\"field\":\"ACTION\",\"value\":\"demo.Charge\"}",
                "{\"op\":\"remove_node\",\"node\":\"route\"}",
                "{\"op\":\"set_start\",\"value\":\"check\"}",
                "{\"op\":\"add_node\",\"type\":\"end\",\"fields\":{\"NAME\":\"last\"}}"), COMMANDS, NODES, "fp");
        assertThat(proposal.preview()).containsExactly("Set NAME of 'done' to 'finished'",
                "Add branch 'check' after 'finished' (operator gte, value_type NUMBER, value 1)",
                "Set ACTION of 'charge' to 'demo.Charge'", "Remove 'route'", "Start the workflow at 'check'", "Add end 'last' at the end of the workflow");
        assertThat(reason(ops("{\"op\":\"remove_node\",\"node\":\"route\"}", "{\"op\":\"set_field\",\"node\":\"route\",\"field\":\"NAME\",\"value\":\"x\"}"))).contains("unknown node 'route'");
        assertThat(WorkflowEditProposals.schema()).contains("\"add_node\",\"set_field\",\"remove_node\",\"set_start\"").doesNotContain("\"run\"");
    }

    // ---- BuildTools ----

    private static Path fakeMaven(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin")); Files.writeString(home.resolve("bin/m2.conf"), "");
        Files.writeString(home.resolve("bin/mvn.cmd"), ""); Files.writeString(home.resolve("bin/mvn"), "");
        Files.createDirectories(home.resolve("boot")); Files.writeString(home.resolve("boot/plexus-classworlds-2.9.0.jar"), "");
        Files.createDirectories(home.resolve("lib")); Files.writeString(home.resolve("lib/maven-core-" + version + ".jar"), "");
        return home;
    }

    @Test void mavenOnPathCustomRepositoryAndJvmConfig() throws Exception {
        Path maven = fakeMaven(Files.createDirectories(temp.resolve("tools/maven")), "3.9.6");
        Path home = Files.createDirectories(temp.resolve("user"));
        Path custom = Files.createDirectories(temp.resolve("custom-repo"));
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><localRepository>" + custom + "</localRepository></settings>");
        Path root = Files.createDirectories(temp.resolve("p"));
        Files.createDirectories(root.resolve(".mvn")); Files.writeString(root.resolve(".mvn/jvm.config"), "-Xmx300m  -Dfoo=bar\n");
        Map<String, String> host = Map.of("PATH", maven.resolve("bin").toString(), "USERPROFILE", home.toString(), "HOME", home.toString(), "SystemRoot", "C:\\Windows");
        var plan = BuildTools.plan(BuildTools.Kind.BUILD, root, "Maven", Path.of(System.getProperty("java.home")), host);
        assertEquals("3.9.6", plan.toolVersion());
        assertThat(plan.command()).contains("-Xmx300m", "-Dfoo=bar", "-Dmaven.repo.local.tail=" + custom);
        assertThat(plan.note()).contains("Maven on PATH");
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><localRepository>${user.home}/x</localRepository></settings>");
        assertThat(BuildTools.plan(BuildTools.Kind.TEST, root, "Maven", Path.of(System.getProperty("java.home")), host).command())
                .contains("-Dmaven.repo.local.tail=" + home.resolve(".m2/repository"));
        var missing = assertThrows(BuildTools.Missing.class, () -> BuildTools.plan(BuildTools.Kind.BUILD, root, "Maven", Path.of(System.getProperty("java.home")),
                Map.of("PATH", "", "USERPROFILE", home.toString(), "HOME", home.toString())));
        assertThat(missing.getMessage()).contains("`mvn` is not on PATH");
        Files.delete(maven.resolve("boot/plexus-classworlds-2.9.0.jar"));
        assertThat(assertThrows(BuildTools.Missing.class, () -> BuildTools.plan(BuildTools.Kind.BUILD, root, "Maven", Path.of(System.getProperty("java.home")), host)).getMessage())
                .contains("incomplete");
    }

    @Test void gradleResolutionAndMissingWrapper() throws Exception {
        Path gradle = Files.createDirectories(temp.resolve("tools/gradle"));
        Files.createDirectories(gradle.resolve("bin")); Files.writeString(gradle.resolve("bin/gradle.bat"), ""); Files.writeString(gradle.resolve("bin/gradle"), "");
        Files.createDirectories(gradle.resolve("lib")); Files.writeString(gradle.resolve("lib/gradle-launcher-8.7.jar"), "");
        Path home = Files.createDirectories(temp.resolve("guser"));
        Path root = Files.createDirectories(temp.resolve("gp"));
        Map<String, String> host = Map.of("PATH", gradle.resolve("bin").toString(), "USERPROFILE", home.toString(), "HOME", home.toString());
        var plan = BuildTools.plan(BuildTools.Kind.TEST, root, "Gradle Kotlin", Path.of(System.getProperty("java.home")), host);
        assertEquals("8.7", plan.toolVersion()); assertThat(plan.command()).contains("check"); assertThat(plan.note()).contains("Gradle on PATH");
        assertThat(assertThrows(BuildTools.Missing.class, () -> BuildTools.plan(BuildTools.Kind.BUILD, root, "Gradle Groovy", Path.of(System.getProperty("java.home")),
                Map.of("PATH", "", "USERPROFILE", home.toString(), "HOME", home.toString()))).getMessage()).contains("`gradle` is not on PATH");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9-all.zip\n");
        var wrapper = assertThrows(BuildTools.Missing.class, () -> BuildTools.plan(BuildTools.Kind.BUILD, root, "Gradle Groovy", Path.of(System.getProperty("java.home")), host));
        assertThat(wrapper.remediation.get(0)).contains("--version` yourself (outside Workbench)").contains("9.9");
    }

    @Test void versionsAndJavaHome() {
        assertTrue(BuildTools.compare("3.9.11", "3.9.0") > 0);
        assertTrue(BuildTools.compare("3.10", "3.9.9") > 0);
        assertEquals(0, BuildTools.compare("3.9", "3.9.0"));
        assertTrue(BuildTools.compare("nonsense", "3.9.0") < 0);
        assertEquals(Path.of(System.getProperty("java.home")), BuildTools.javaHome(Map.of("JAVA_HOME", temp.resolve("no-jdk").toString())));
        assertEquals(Path.of(System.getProperty("java.home")), BuildTools.javaHome(Map.of("JAVA_HOME", System.getProperty("java.home"))));
    }

    // ---- ConversationStore ----

    @Test void conversationStorageEdges() throws Exception {
        Path root = Files.createDirectories(temp.resolve("conv"));
        var store = new ConversationStore(relative -> root.resolve(relative).normalize());
        assertEquals(List.of(), store.list());
        assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
        assertThrows(NoSuchFileException.class, () -> store.load(UUID.randomUUID().toString()));
        var empty = ConversationStore.Conversation.fresh();
        assertFalse(store.save(empty), "Empty conversations are not written");
        var long1 = empty.with(ConversationStore.Message.user("x".repeat(80))).with(new ConversationStore.Message("assistant", "excerpt", "complete", "", true));
        assertTrue(store.save(long1));
        var loaded = store.load(long1.id());
        assertEquals(61, loaded.title().length(), "Titles are truncated to 60 characters plus an ellipsis");
        assertEquals(1, loaded.messages().size(), "Transient diagnostic context is never persisted");
        Files.writeString(root.resolve(ConversationStore.DIRECTORY + "/" + UUID.randomUUID() + ".json"), "{damaged");
        Files.writeString(root.resolve(ConversationStore.DIRECTORY + "/notes.txt"), "ignored");
        assertThat(store.list()).extracting(ConversationStore.Summary::id).containsExactly(long1.id());
        store.delete(long1.id());
        assertFalse(store.save(long1.with(ConversationStore.Message.user("late"))), "A deleted conversation cannot be recreated by a late callback");
    }

    // ---- Sandbox helpers ----

    @Test void probeClassifiesEveryAttemptWhenRunUnconfined() throws Exception {
        Path root = Files.createDirectories(temp.resolve("probe root"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "x");
        Path cache = Files.createDirectories(temp.resolve("cache"));
        var out = new java.io.ByteArrayOutputStream(); var previous = System.out;
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            System.setOut(new java.io.PrintStream(out, true));
            SandboxProbe.main(new String[]{sentinel.toString(), outside.toString(), root.toString(), Integer.toString(server.getLocalPort()), cache.toString(), temp.resolve("missing-cache").toString()});
        } finally { System.setOut(previous); }
        String text = out.toString();
        assertThat(text).contains("probe:outsideRead=allowed", "probe:outsideWrite=allowed", "probe:rootWrite=allowed", "probe:javaTempFiles=allowed",
                "probe:socketsAvailable=yes", "probe:networkLoopbackOutside=connected", "probe:cacheRead:0=allowed", "probe:cacheWrite:0=allowed",
                "probe:cacheRead:1=denied", "probe:cacheWrite:1=denied", "probe:descendantStarted=", "probe:networkReserved=");
        assertFalse(Files.list(cache).findAny().isPresent(), "The probe removes its own cache marker");
        Path marker = temp.resolve("descendant.txt");
        SandboxProbe.main(new String[]{"descendant", marker.toString()});
        assertEquals("descendant survived", Files.readString(marker));
    }

    @Test void macProfileIsDenyByDefaultAndRejectsUnsafePaths() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mac root"));
        Path cache = Files.createDirectories(temp.resolve("mac cache"));
        if (System.getProperty("os.name").startsWith("Mac")) {
            String profile = MacSandbox.profile(root, List.of(cache, temp.resolve("not-there")));
            assertThat(profile).startsWith("(version 1)\n(deny default)").contains("(deny network*)").contains("(subpath \"" + cache.toRealPath() + "\")")
                    .contains("(allow file-write* (subpath \"" + root.toRealPath() + "\")").contains("global-name \"com.apple.system.logger\"")
                    .doesNotContain("(allow network");
            assertThrows(IllegalArgumentException.class, () -> MacSandbox.profile(Files.createDirectories(temp.resolve("bad\"quote")), List.of()));
        } else {
            // Backslashes and quotes cannot appear in a sandbox profile string, so such paths are refused rather than escaped.
            assertThrows(IllegalArgumentException.class, () -> MacSandbox.profile(root, List.of(cache)));
        }
        var mac = new MacSandbox();
        assertEquals("macOS sandbox-exec + process group", mac.mechanism());
        assertThat(mac.setup(root, List.of()).note()).contains("no permanent permission change");
        mac.applyWorkbenchGrants(root);
        if (!System.getProperty("os.name").startsWith("Mac")) {
            assertThat(mac.unavailableReason()).contains("sandbox-exec");
            assertThrows(IllegalStateException.class, () -> mac.start(new Sandbox.Launch(List.of("x"), root, Map.of(), root, List.of()), b -> { }));
        }
        var unsupported = new Sandbox.Unsupported("not here");
        assertEquals("none", unsupported.mechanism());
        assertThrows(IllegalStateException.class, () -> unsupported.applyWorkbenchGrants(root));
        assertThrows(IllegalStateException.class, () -> unsupported.start(null, b -> { }));
        assertEquals("not here", unsupported.setup(root, List.of()).note());
    }
}
