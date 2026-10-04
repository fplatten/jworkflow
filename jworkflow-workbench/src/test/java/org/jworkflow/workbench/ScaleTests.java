package org.jworkflow.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AT-10 scale and timing on the declared baseline: a 500-block workflow with 100 distinct branch variables saves and
 * reopens within two seconds and generates core-accepted Groovy. Timings are written to target/performance-java.txt.
 * The supported palette nests at most two levels (start → nodes), so the 10-level nesting target has no construct to exercise.
 */
class ScaleTests {
    @TempDir Path temp;

    /** Start + 300 steps + 100 branches + 99 ends = 500 blocks, one chain under the start block. */
    static String fiveHundredBlocks() {
        List<String> nodes = new ArrayList<>();
        int steps = 0, branches = 0;
        for (int i = 0; i < 400; i++) {
            String name = "n" + i, next = i == 399 ? "e0" : "n" + (i + 1);
            if (i % 4 == 3 && branches < 100) {
                nodes.add("{\"type\":\"workflow_branch\",\"id\":\"b" + branches + "\",\"fields\":{\"NAME\":\"" + name + "\",\"VARIABLE\":\"var" + branches
                        + "\",\"OPERATOR\":\"gte\",\"VALUE_TYPE\":\"NUMBER\",\"VALUE\":\"" + branches + ".5\",\"TRUE_TARGET\":\"" + next + "\",\"FALSE_TARGET\":\"e1\"}");
                branches++;
            } else {
                nodes.add("{\"type\":\"workflow_step\",\"id\":\"s" + steps + "\",\"fields\":{\"NAME\":\"" + name + "\",\"ACTION\":\"DoThing\",\"SUCCESS\":\"" + next + "\",\"FAILURE\":\"e2\"}");
                steps++;
            }
        }
        for (int i = 0; i < 99; i++) nodes.add("{\"type\":\"workflow_end\",\"id\":\"end" + i + "\",\"fields\":{\"NAME\":\"e" + i + "\"}");
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) chain.append(nodes.get(i)).append(i == nodes.size() - 1 ? "" : ",\"next\":{\"block\":");
        chain.append("}").append("}}".repeat(nodes.size() - 1));
        return "{\"blocks\":{\"languageVersion\":0,\"blocks\":[{\"type\":\"workflow_start\",\"id\":\"start\",\"x\":10,\"y\":10,\"fields\":{\"START\":\"n0\"},\"inputs\":{\"NODES\":{\"block\":" + chain + "}}}]}}";
    }

    @Test void fiveHundredBlocksSaveReopenAndGenerateWithinBudgets() throws Exception {
        Path root = Files.createDirectories(temp.resolve("scale"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        Files.writeString(Files.createDirectories(root.resolve("src/main/java/demo")).resolve("DoThing.java"),
                "package demo; import org.jworkflow.application.Command; public record DoThing(String id) implements Command {}");
        var workspace = new ProjectWorkspace(new LastProjectPreference(temp.resolve("pref.txt")));
        workspace.confirm(root.toString());
        String blockly = fiveHundredBlocks();
        long worstSaveReopen = 0;
        ProjectWorkspace.DraftView saved = null;
        for (int run = 0; run < 5; run++) {
            long started = System.nanoTime();
            saved = workspace.saveDraft(new ProjectWorkspace.SaveDraft(saved == null ? "" : saved.documentId(), "Scale", "scale", "1.0." + run, "demo",
                    "src/main/resources/workflows", saved == null ? 0 : saved.revision(), saved == null ? "" : saved.hash(), blockly));
            var reopened = workspace.loadDraft();
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (run > 0) worstSaveReopen = Math.max(worstSaveReopen, millis);   // run 0 includes JIT warm-up
            assertEquals(saved.hash(), reopened.hash());
        }
        long started = System.nanoTime();
        var result = workspace.generate(new ProjectWorkspace.GenerateRequest(saved.revision(), saved.hash()));
        long generateMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals("ready", result.state(), result.diagnostics().toString());
        assertThat(result.groovy()).contains("step('n0')").contains("variable: 'var99'");
        assertThat(result.sourceMap()).hasSize(500);
        long compileStarted = System.nanoTime();
        new org.jworkflow.dsl.GroovyWorkflowDslCompiler().compile(new org.jworkflow.definition.WorkflowDefinitionText("scale.groovy", result.groovy()));
        long compileMillis = (System.nanoTime() - compileStarted) / 1_000_000;
        System.out.println("Core compile alone (second run): " + compileMillis + " ms");
        long blocks = blockly.split("\"type\":\"workflow_").length - 1;
        assertEquals(500, blocks);
        String report = "Scale fixture: " + blocks + " blocks, 100 branch variables, one 499-node chain\n"
                + "Save + reopen (worst of 4 warm runs): " + worstSaveReopen + " ms (budget 2000 ms)\n"
                + "Generate incl. validation, emission and core compile: " + generateMillis + " ms (no budget; recorded for G-09)\n"
                + "Document size: " + Files.size(root.resolve(".jworkflow/workflow.jworkflow.json")) + " bytes\n";
        Files.writeString(Path.of("target", "performance-java.txt"), report);
        System.out.print(report);
        assertTrue(worstSaveReopen < 2000, report);
    }
}
