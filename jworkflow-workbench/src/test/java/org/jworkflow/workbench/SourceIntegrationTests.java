package org.jworkflow.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import org.jworkflow.engine.StepHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WB-14–WB-17 end to end: proposal, whole-set approval, Java 17 compilation, core execution, revert and plan. */
class SourceIntegrationTests {
    @TempDir Path temp;

    private static final String WORKFLOW = """
            {"blocks":{"languageVersion":0,"blocks":[{"type":"workflow_start","id":"s","fields":{"START":"charge"},
             "inputs":{"NODES":{"block":{"type":"workflow_step","id":"a","fields":{"NAME":"charge","ACTION":"OrderCommand","SUCCESS":"ship","FAILURE":"failed"},
             "next":{"block":{"type":"workflow_step","id":"b","fields":{"NAME":"ship","ACTION":"Ship","SUCCESS":"done","FAILURE":"failed"},
             "next":{"block":{"type":"workflow_end","id":"e1","fields":{"NAME":"done"},
             "next":{"block":{"type":"workflow_end","id":"e2","fields":{"NAME":"failed"}}}}}}}}}}}]}}
            """;

    private Path project() throws IOException {
        Path root = Files.createDirectories(temp.resolve("shop project"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.jworkflow</groupId><artifactId>jworkflow-core</artifactId><version>0.1.0-SNAPSHOT</version></dependency></dependencies></project>");
        Path command = Files.createDirectories(root.resolve("src/main/java/shop")).resolve("OrderCommand.java");
        Files.writeString(command, "package shop;\n\nimport java.math.BigDecimal;\nimport org.jworkflow.application.Command;\n\n"
                + "/** User-written command. */\npublic record OrderCommand(String businessKey, @Deprecated BigDecimal amount, long quantity) implements Command {}\n");
        return root;
    }

    private ProjectWorkspace workspace() { return new ProjectWorkspace(new LastProjectPreference(temp.resolve("pref/last.txt"))); }

    private ProjectWorkspace.DraftView save(ProjectWorkspace workspace, String engineId, ProjectWorkspace.DraftView previous) throws IOException {
        return workspace.saveDraft(new ProjectWorkspace.SaveDraft(previous == null ? "" : previous.documentId(), "Order flow", engineId, "1.0.0", "shop.flow",
                "src/main/resources/workflows", previous == null ? 0 : previous.revision(), previous == null ? "" : previous.hash(), WORKFLOW));
    }

    @Test void proposesAppliesCompilesRunsAndRevertsTheWholeChangeSet() throws Exception {
        Path root = project(); var workspace = workspace(); workspace.confirm(root.toString());
        var draft = save(workspace, "order-flow", null);
        var generated = workspace.generate(new ProjectWorkspace.GenerateRequest(draft.revision(), draft.hash()));
        assertThat(generated.state()).withFailMessage(generated.diagnostics().toString()).isEqualTo("ready");
        assertThat(generated.diagnostics()).extracting(WorkflowGenerationService.Diagnostic::code).containsExactly("COMMAND_WILL_BE_CREATED");
        assertThat(workspace.plan()).contains("Proposed only; nothing has been applied").contains("Static validation (Generate) | Passed for revision 1");

        var proposal = workspace.proposeChanges(new ProjectWorkspace.GenerateRequest(draft.revision(), draft.hash()));
        assertThat(proposal.files()).extracting(ProjectWorkspace.ProposedFile::path).containsExactly(
                "src/main/resources/workflows/order-flow.groovy", "src/main/java/shop/OrderCommandListener.java",
                "src/main/java/shop/flow/Ship.java", "src/main/java/shop/flow/ShipListener.java");
        assertThat(proposal.files()).allSatisfy(file -> assertThat(file.diff()).startsWith("--- /dev/null"));
        assertThat(proposal.artifacts()).anySatisfy(a -> { assertThat(a.className()).isEqualTo("shop.OrderCommand"); assertThat(a.status()).isEqualTo("kept"); });
        assertFalse(Files.exists(root.resolve("src/main/java/shop/flow")), "Reviewing must not write source");

        // A stale approval token never writes.
        assertThrows(ProjectWorkspace.Conflict.class, () -> workspace.applyChanges(new ProjectWorkspace.ApplyRequest("stale", draft.revision(), draft.hash(), false)));
        var applied = workspace.applyChanges(new ProjectWorkspace.ApplyRequest(proposal.token(), draft.revision(), draft.hash(), false));
        assertEquals("applied", applied.status());
        assertThat(applied.plan()).contains("Applied for revision 1").contains(".stepHandler(\"Ship\", new ShipListener(yourService::handle))")
                .contains("ClasspathWorkflowDefinitionSource(List.of(\"workflows/order-flow.groovy\"))")
                .contains("amount ← variables[\"amount\"] as BigDecimal").contains("businessKey ← workflow business key");
        String listener = Files.readString(root.resolve("src/main/java/shop/OrderCommandListener.java"));
        assertThat(listener).contains("implements StepHandler").doesNotContain("@Component").doesNotContain("@Service").contains("((Number) context.variables().get(\"quantity\")).longValue()");

        // AT-18: generated Java compiles at Java 17 with only the core and runs the generated workflow on the core engine.
        ClassLoader loader = compile(root);
        List<Object> handled = new ArrayList<>();
        Consumer<Object> service = handled::add;
        StepHandler order = (StepHandler) loader.loadClass("shop.OrderCommandListener").getConstructor(Consumer.class).newInstance(service);
        StepHandler ship = (StepHandler) loader.loadClass("shop.flow.ShipListener").getConstructor(Consumer.class).newInstance(service);
        var definition = new org.jworkflow.dsl.GroovyWorkflowDslCompiler().compile(new org.jworkflow.definition.WorkflowDefinitionText("order-flow.groovy",
                Files.readString(root.resolve("src/main/resources/workflows/order-flow.groovy"))));
        try (var engine = org.jworkflow.engine.WorkflowEngine.builder().definition(definition).stepHandler("OrderCommand", order).stepHandler("Ship", ship).build()) {
            var id = engine.start("order-flow", "order-7", Map.of("amount", new BigDecimal("12.50"), "quantity", 3));
            int signals = 0;
            while (engine.snapshot(id).status() == org.jworkflow.model.WorkflowStatus.RUNNING && signals < 4) {
                engine.signal(id, new org.jworkflow.model.WorkflowSignal("order.requested", "order-7", null, "order-7", java.time.Instant.now(), Map.of()));
                signals++;
            }
            assertEquals("done", engine.snapshot(id).state());
            assertEquals(2, signals, "Each sequential step runs on its own signal");
        }
        assertEquals(2, handled.size());
        assertEquals("OrderCommand[businessKey=order-7, amount=12.50, quantity=3]", handled.get(0).toString());
        assertThat(handled.get(1).toString()).startsWith("Ship[businessKey=order-7, variables=");

        // Repeat apply is idempotent; later edits invalidate the revert, and an untouched set reverts completely.
        var again = workspace.proposeChanges(new ProjectWorkspace.GenerateRequest(draft.revision(), draft.hash()));
        assertThat(again.files()).isEmpty();
        assertEquals("already-applied", workspace.applyChanges(new ProjectWorkspace.ApplyRequest(again.token(), draft.revision(), draft.hash(), false)).status());
        assertTrue(workspace.state().revertAvailable());
        Path shipSource = root.resolve("src/main/java/shop/flow/Ship.java");
        String original = Files.readString(shipSource);
        Files.writeString(shipSource, original + "// user edit\n");
        var refused = assertThrows(ProjectWorkspace.Conflict.class, workspace::proposeRevert);
        assertThat(refused.getMessage()).contains("src/main/java/shop/flow/Ship.java");
        Files.writeString(shipSource, original);
        var revert = workspace.proposeRevert();
        assertThat(revert.files()).extracting(ProjectWorkspace.ProposedFile::change).containsOnly("delete");
        assertEquals("reverted", workspace.applyRevert(revert.token()).status());
        assertFalse(Files.exists(shipSource)); assertFalse(Files.exists(root.resolve("src/main/resources/workflows/order-flow.groovy")));
        assertTrue(Files.exists(root.resolve("src/main/java/shop/OrderCommand.java")), "User command is never touched");
        assertFalse(workspace.state().revertAvailable());
        assertThat(workspace.plan()).contains("Proposed only; nothing has been applied");
    }

    @Test void manualGroovyEditsAndUserListenersNeedExplicitHandling() throws Exception {
        Path root = project(); var workspace = workspace(); workspace.confirm(root.toString());
        var draft = save(workspace, "order-flow", null);
        workspace.generate(new ProjectWorkspace.GenerateRequest(draft.revision(), draft.hash()));
        Path groovy = Files.createDirectories(root.resolve("src/main/resources/workflows")).resolve("order-flow.groovy");
        Files.writeString(groovy, "workflow('order-flow') { /* hand written */ }\n");
        Files.writeString(root.resolve("src/main/java/shop/OrderCommandListener.java"), "package shop;\n/** Mine. */\npublic class OrderCommandListener {}\n");
        var proposal = workspace.proposeChanges(new ProjectWorkspace.GenerateRequest(draft.revision(), draft.hash()));
        assertTrue(proposal.manualGroovyEdit());
        assertThat(proposal.files()).extracting(ProjectWorkspace.ProposedFile::path).doesNotContain("src/main/java/shop/OrderCommandListener.java");
        assertThat(proposal.discrepancies()).anySatisfy(d -> assertThat(d).contains("OrderCommandListener.java").contains("was not changed"));
        assertThrows(ProjectWorkspace.Conflict.class, () -> workspace.applyChanges(new ProjectWorkspace.ApplyRequest(proposal.token(), draft.revision(), draft.hash(), false)));
        assertEquals("workflow('order-flow') { /* hand written */ }\n", Files.readString(groovy));
        assertEquals("applied", workspace.applyChanges(new ProjectWorkspace.ApplyRequest(proposal.token(), draft.revision(), draft.hash(), true)).status());
        assertThat(Files.readString(groovy)).startsWith("workflow('order-flow')").contains("step('charge')");
        assertEquals("package shop;\n/** Mine. */\npublic class OrderCommandListener {}\n", Files.readString(root.resolve("src/main/java/shop/OrderCommandListener.java")));

        // Rename: old Groovy stays in place and is listed for manual cleanup; nothing is deleted automatically.
        var renamed = save(workspace, "order-flow-v2", workspace.loadDraft());
        workspace.generate(new ProjectWorkspace.GenerateRequest(renamed.revision(), renamed.hash()));
        var next = workspace.proposeChanges(new ProjectWorkspace.GenerateRequest(renamed.revision(), renamed.hash()));
        assertThat(next.files()).extracting(ProjectWorkspace.ProposedFile::change).doesNotContain("delete");
        workspace.applyChanges(new ProjectWorkspace.ApplyRequest(next.token(), renamed.revision(), renamed.hash(), false));
        assertTrue(Files.exists(groovy));
        assertThat(workspace.plan()).contains("The engine ID changed from `order-flow` to `order-flow-v2`")
                .contains("`src/main/resources/workflows/order-flow.groovy` was applied earlier but is no longer part of this workflow");
    }

    @Test void diffShowsEveryChangedLine() {
        String diff = TextDiff.unified("a.txt", "one\ntwo\nthree\n", "one\n2\nthree\nfour\n");
        assertThat(diff).contains("--- a/a.txt").contains("+++ b/a.txt").contains("-two").contains("+2").contains("+four").contains(" one");
        assertThat(TextDiff.unified("gone.txt", "x\n", null)).contains("+++ /dev/null").contains("-x");
    }

    private ClassLoader compile(Path root) throws IOException {
        Path classes = Files.createDirectories(temp.resolve("classes"));
        List<String> sources;
        try (var walk = Files.walk(root.resolve("src/main/java"))) { sources = walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList(); }
        List<String> args = new ArrayList<>(List.of("--release", "17", "-Xlint:all,-deprecation", "-Werror", "-proc:none", "-d", classes.toString(), "-cp", System.getProperty("java.class.path")));
        args.addAll(sources);
        var errors = new java.io.ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, errors, errors, args.toArray(String[]::new));
        assertEquals(0, status, errors.toString());
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }
}
