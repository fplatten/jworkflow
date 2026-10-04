package org.jworkflow.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** WB-11 diagnostic matrix (AT-06/09/12/13): every rule that blocks or warns, and safe handling of unknown input. */
class ValidationMatrixTests {
    private final WorkflowGenerationService service = new WorkflowGenerationService();
    private static final List<ProjectWorkspace.Binding> COMMANDS = List.of(new ProjectWorkspace.Binding("command", "demo", "DoThing", List.of(), "src/main/java/demo/DoThing.java", 1, ""));

    private static ProjectWorkspace.DraftView draft(String name, String engineId, String version, String javaPackage, String blockly) {
        return new ProjectWorkspace.DraftView("doc", name, engineId, version, javaPackage, "src/main/resources/workflows", 1, "h", blockly);
    }
    private List<String> codes(String blockly) { return codes(draft("Flow", "flow", "1", "demo", blockly)); }
    private List<String> codes(ProjectWorkspace.DraftView draft) {
        return service.generate(draft, COMMANDS, false, "").diagnostics().stream().map(WorkflowGenerationService.Diagnostic::code).collect(Collectors.toList());
    }
    private static String start(String target, String chain) {
        return "{\"blocks\":{\"blocks\":[{\"type\":\"workflow_start\",\"id\":\"s\",\"fields\":{\"START\":\"" + target + "\"}" + (chain.isEmpty() ? "" : ",\"inputs\":{\"NODES\":{\"block\":" + chain + "}}") + "}]}}";
    }
    private static String step(String id, String fields, String next) {
        return "{\"type\":\"workflow_step\",\"id\":\"" + id + "\",\"fields\":{" + fields + "}" + (next.isEmpty() ? "" : ",\"next\":{\"block\":" + next + "}") + "}";
    }
    private static String branch(String fields, String next) {
        return "{\"type\":\"workflow_branch\",\"id\":\"b\",\"fields\":{" + fields + "}" + (next.isEmpty() ? "" : ",\"next\":{\"block\":" + next + "}") + "}";
    }
    private static String end(String name) { return "{\"type\":\"workflow_end\",\"id\":\"e-" + name + "\",\"fields\":{\"NAME\":\"" + name + "\"}}"; }

    @Test void structureRules() {
        assertThat(codes("not json")).contains("DOCUMENT_INVALID", "START_REQUIRED");
        assertThat(codes("{}")).containsExactly("START_REQUIRED");
        assertThat(codes("{\"blocks\":{\"blocks\":[{\"type\":\"workflow_start\",\"id\":\"a\",\"fields\":{\"START\":\"x\"}},{\"type\":\"workflow_start\",\"id\":\"b\"}]}}"))
                .contains("START_MULTIPLE", "REFERENCE_UNKNOWN").doesNotContain("BLOCK_ORPHAN");
        assertThat(codes(start("", end("done")))).contains("START_TARGET_REQUIRED");
        assertThat(codes("{\"blocks\":{\"blocks\":[{\"type\":\"workflow_start\",\"id\":\"s\",\"fields\":{\"START\":\"done\"},\"inputs\":{\"NODES\":{\"block\":" + end("done") + "}}},"
                + "{\"type\":\"workflow_mystery\",\"id\":\"m\"}]}}")).contains("BLOCK_UNSUPPORTED", "BLOCK_ORPHAN");
        // Disabled detached blocks warn in every serialized form; enabled ones block.
        for (String disabled : List.of("\"disabled\":true", "\"enabled\":false", "\"disabledReasons\":[\"MANUALLY_DISABLED\"]")) {
            var result = service.generate(draft("Flow", "flow", "1", "demo", "{\"blocks\":{\"blocks\":[" + start("done", end("done")).replaceAll("^\\{\"blocks\":\\{\"blocks\":\\[|\\]\\}\\}$", "")
                    + ",{\"type\":\"workflow_end\",\"id\":\"x\"," + disabled + ",\"fields\":{\"NAME\":\"spare\"}}]}}"), COMMANDS, false, "");
            assertEquals("ready", result.state(), disabled + " " + result.diagnostics());
            assertThat(result.diagnostics()).extracting(WorkflowGenerationService.Diagnostic::code).containsExactly("BLOCK_ORPHAN_DISABLED");
        }
    }

    @Test void stepAndBranchFieldRules() {
        assertThat(codes(start("a", step("a", "\"NAME\":\"\"", "")))).contains("NODE_NAME_REQUIRED", "ACTION_REQUIRED", "SUCCESS_REQUIRED", "FAILURE_REQUIRED");
        assertThat(codes(start("a", step("a", "\"NAME\":\"a\",\"ACTION\":\"DoThing\",\"SUCCESS\":\"a\",\"FAILURE\":\"a\"", step("b", "\"NAME\":\"a\",\"ACTION\":\"DoThing\",\"SUCCESS\":\"a\",\"FAILURE\":\"a\"", "")))))
                .contains("NODE_NAME_DUPLICATE");
        assertThat(codes(start("r", branch("\"NAME\":\"r\",\"OPERATOR\":\"like\",\"VALUE_TYPE\":\"DATE\"", "")))).contains("VARIABLE_REQUIRED", "OPERATOR_UNSUPPORTED", "LITERAL_TYPE_UNSUPPORTED", "TRUE_TARGET_REQUIRED", "FALSE_TARGET_REQUIRED");
        String common = "\"NAME\":\"r\",\"VARIABLE\":\"v\",\"OPERATOR\":\"eq\",\"TRUE_TARGET\":\"done\",\"FALSE_TARGET\":\"alt\",";
        String ends = end("done").replace("}}", "},\"next\":{\"block\":" + end("alt") + "}}");
        assertThat(codes(start("r", branch(common + "\"VALUE_TYPE\":\"NUMBER\",\"VALUE\":\"1e\"", ends)))).containsExactly("LITERAL_NUMBER_INVALID");
        assertThat(codes(start("r", branch(common + "\"VALUE_TYPE\":\"BOOLEAN\",\"VALUE\":\"yes\"", ends)))).containsExactly("LITERAL_BOOLEAN_INVALID");
        assertThat(codes(start("r", branch(common + "\"VALUE_TYPE\":\"STRING\",\"VALUE\":\"\"", ends)))).containsExactly("LITERAL_REQUIRED");
        var nullBranch = service.generate(draft("Flow", "flow", "1", "demo", start("r", branch(common + "\"VALUE_TYPE\":\"NULL\"", ends))), COMMANDS, false, "");
        assertEquals("ready", nullBranch.state(), nullBranch.diagnostics().toString());
        assertThat(nullBranch.groovy()).contains("eq: null");
        var bool = service.generate(draft("Flow", "flow", "1", "demo", start("r", branch(common + "\"VALUE_TYPE\":\"BOOLEAN\",\"VALUE\":\"false\"", ends))), COMMANDS, false, "");
        assertThat(bool.groovy()).contains("eq: false");
        // The pinned core rejects identical transition targets, so validation reports them on the field.
        var identicalStep = service.generate(draft("Flow", "flow", "1", "demo", start("a", step("a", "\"NAME\":\"a\",\"ACTION\":\"DoThing\",\"SUCCESS\":\"done\",\"FAILURE\":\"done\"", end("done")))), COMMANDS, false, "");
        assertThat(identicalStep.diagnostics()).singleElement().satisfies(d -> { assertEquals("TARGETS_IDENTICAL", d.code()); assertEquals("a", d.blockId()); assertEquals("FAILURE", d.field()); });
        assertThat(codes(start("r", branch(common.replace("\"alt\"", "\"done\"") + "\"VALUE_TYPE\":\"STRING\",\"VALUE\":\"x\"", end("done"))))).containsExactly("TARGETS_IDENTICAL");
        // Field values may arrive as {"value": …} objects from other serializers.
        var objectField = service.generate(draft("Flow", "flow", "1", "demo", start("done", "{\"type\":\"workflow_end\",\"id\":\"e\",\"fields\":{\"NAME\":{\"value\":\"done\"}}}")), COMMANDS, false, "");
        assertEquals("ready", objectField.state(), objectField.diagnostics().toString());
    }

    @Test void metadataPackageAndActionRules() {
        String ok = start("a", step("a", "\"NAME\":\"a\",\"ACTION\":\"NewThing\",\"SUCCESS\":\"done\",\"FAILURE\":\"failed\"", end("done").replace("}}", "},\"next\":{\"block\":" + end("failed") + "}}")));
        assertThat(codes(draft("", "", "", "demo", ok))).contains("META_NAME_REQUIRED", "META_ENGINE_ID_REQUIRED", "META_VERSION_REQUIRED");
        assertThat(codes(draft("Flow", "9flow", "1", "demo", ok))).contains("META_ENGINE_ID_INVALID");
        assertThat(codes(draft("Flow", "flow", "1", "demo..x", ok))).contains("JAVA_PACKAGE_INVALID");
        assertThat(codes(draft("Flow", "flow", "1", "", ok))).contains("JAVA_PACKAGE_REQUIRED");
        assertThat(codes(draft("Flow", "flow", "1", "demo", ok))).containsExactly("COMMAND_WILL_BE_CREATED");
        assertThat(codes(draft("Flow", "flow", "1", "demo", ok.replace("NewThing", "class")))).contains("ACTION_UNKNOWN");
        assertThat(codes(draft("Flow", "flow", "1", "demo", ok.replace("NewThing", "demo.DoThing")))).isEmpty();
        assertTrue(WorkflowGenerationService.isJavaPackage("a.b_c.$d"));
        for (String bad : new String[]{"", "a.", ".a", "1a", "a.int", "a-b"}) assertFalse(WorkflowGenerationService.isJavaPackage(bad), bad);
        assertFalse(WorkflowGenerationService.isJavaIdentifier(null));
        assertEquals(List.of(), WorkflowGenerationService.stepActions("{}"));
        assertEquals(List.of("NewThing"), WorkflowGenerationService.stepActions(ok));
    }

    @Test void limitsAreBoundedFailures() {
        String chain = IntStream.range(0, 501).mapToObj(i -> "{\"type\":\"workflow_end\",\"id\":\"e" + i + "\",\"fields\":{\"NAME\":\"n" + i + "\"},\"next\":{\"block\":")
                .collect(Collectors.joining()) + "null" + "}}".repeat(501);
        assertThat(codes(start("n0", chain))).contains("LIMIT_BLOCKS");
        String nested = "{\"type\":\"workflow_mystery\",\"id\":\"deep\"}";
        for (int i = 0; i < 11; i++) nested = "{\"type\":\"workflow_mystery\",\"id\":\"m" + i + "\",\"inputs\":{\"X\":{\"block\":" + nested + "}}}";
        assertThat(codes("{\"blocks\":{\"blocks\":[" + nested + "]}}")).contains("LIMIT_DEPTH", "BLOCK_UNSUPPORTED");
        var shared = service.generate(draft("", "", "", "demo", "{}"), COMMANDS, false, "").sharedRules();
        assertThat(shared).extracting(WorkflowGenerationService.SharedRuleResult::result).containsExactly(1001, 1001, 1001, 0, 0);
    }

    @Test void unsupportedBlockDetectionNeverDropsUnknownContent() {
        assertFalse(WorkflowGenerationService.hasUnsupportedBlock(null));
        assertFalse(WorkflowGenerationService.hasUnsupportedBlock(" "));
        assertFalse(WorkflowGenerationService.hasUnsupportedBlock("{\"blocks\":null}"));
        assertFalse(WorkflowGenerationService.hasUnsupportedBlock(start("done", end("done"))));
        assertTrue(WorkflowGenerationService.hasUnsupportedBlock("{oops"));
        assertTrue(WorkflowGenerationService.hasUnsupportedBlock("{\"blocks\":[]}"));
        assertTrue(WorkflowGenerationService.hasUnsupportedBlock("{\"blocks\":{\"blocks\":{}}}"));
        assertTrue(WorkflowGenerationService.hasUnsupportedBlock(start("x", "{\"type\":\"future_block\",\"id\":\"f\"}")));
        assertTrue(WorkflowGenerationService.hasUnsupportedBlock(start("x", step("a", "\"NAME\":\"a\"", "{\"type\":\"future_block\",\"id\":\"f\"}"))));
    }

    @Test void quotingEscapesEveryControlCharacter() {
        assertEquals("'a\\\\b\\'c\\nd\\re\\tf\\u0001'", WorkflowGenerationService.quote("a\\b'c\nd\re\tf\u0001"));
    }
}
