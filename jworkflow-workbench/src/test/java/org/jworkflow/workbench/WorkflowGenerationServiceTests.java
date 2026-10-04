package org.jworkflow.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowGenerationServiceTests {
    private final WorkflowGenerationService service = new WorkflowGenerationService();
    private final List<ProjectWorkspace.Binding> commands = List.of(new ProjectWorkspace.Binding(
            "command", "demo", "DoThing", List.of(), "src/main/java/demo/DoThing.java", 1, ""));

    @Test void emitsDeterministicCoreAcceptedSequenceBranchAndFailureDefinition() {
        var first = service.generate(draft(validState(false, "NUMBER", "1234567890.12345678901234567890")), commands, false, "");
        var second = service.generate(draft(validState(true, "NUMBER", "1234567890.12345678901234567890")), commands, false, "");
        assertThat(first.state()).withFailMessage(first.diagnostics().toString()).isEqualTo("ready");
        assertThat(first.diagnostics()).isEmpty();
        assertThat(first.groovy()).isEqualTo(second.groovy())
                .contains("onFailure goTo: 'failed'")
                .contains("1234567890.12345678901234567890")
                .contains("otherwise goTo: 'failed'");
        assertThat(first.sourceMap()).extracting(WorkflowGenerationService.SourceRange::blockId)
                .containsExactly("start", "step", "branch", "done", "failed");
    }

    @Test void escapesHostileStringsWithoutInterpolationOrExecution() {
        var result = service.generate(draft(validState(false, "STRING", "x'$value\\nnext")), commands, false, "");
        assertThat(result.state()).withFailMessage(result.diagnostics().toString()).isEqualTo("ready");
        assertThat(result.groovy()).contains("'x\\'$value\\\\nnext'");
    }

    @Test void invalidDraftIsDeterministicAndBlocksCodeButDisabledOrphanWarns() {
        String state = """
                {"blocks":{"languageVersion":0,"blocks":[
                  {"type":"workflow_start","id":"start","fields":{"START":"missing"}},
                  {"type":"workflow_step","id":"orphan","enabled":false,"fields":{"NAME":"x","ACTION":"Unknown","SUCCESS":"x","FAILURE":"x"}}
                ]}}
                """;
        var result = service.generate(draft(state), commands, false, "");
        assertThat(result.state()).isEqualTo("blocked");
        assertThat(result.groovy()).isEmpty();
        assertThat(result.diagnostics()).extracting(WorkflowGenerationService.Diagnostic::code)
                .contains("BLOCK_ORPHAN_DISABLED", "REFERENCE_UNKNOWN");
    }

    @Test void sharedRulesMatchBoundaryContract() {
        assertThat(SharedValidationRules.check(SharedValidationRules.REQUIRED, 0)).isEqualTo(1001);
        assertThat(SharedValidationRules.check(SharedValidationRules.REQUIRED, 1)).isZero();
        assertThat(SharedValidationRules.check(SharedValidationRules.MAX_BLOCKS, 500)).isZero();
        assertThat(SharedValidationRules.check(SharedValidationRules.MAX_BLOCKS, 501)).isEqualTo(1002);
        assertThat(SharedValidationRules.check(SharedValidationRules.MAX_DEPTH, 10)).isZero();
        assertThat(SharedValidationRules.check(SharedValidationRules.MAX_DEPTH, 11)).isEqualTo(1003);
        assertThat(SharedValidationRules.check(999, 0)).isEqualTo(1099);
    }

    /** AT-08/AT-17: generated Groovy runs on the pinned core engine alone; Workbench is not on the execution path. */
    @Test void generatedDefinitionExecutesSequenceBothBranchOutcomesAndFailureOnPinnedCore() throws Exception {
        String state = """
                {"blocks":{"languageVersion":0,"blocks":[{"type":"workflow_start","id":"s","fields":{"START":"charge"},
                 "inputs":{"NODES":{"block":{"type":"workflow_step","id":"a","fields":{"NAME":"charge","ACTION":"DoThing","SUCCESS":"route","FAILURE":"errored"},
                 "next":{"block":{"type":"workflow_branch","id":"b","fields":{"NAME":"route","VARIABLE":"amount","OPERATOR":"gte","VALUE_TYPE":"NUMBER","VALUE":"100.50","TRUE_TARGET":"large","FALSE_TARGET":"small"},
                 "next":{"block":{"type":"workflow_end","id":"e1","fields":{"NAME":"large"},
                 "next":{"block":{"type":"workflow_end","id":"e2","fields":{"NAME":"small"},
                 "next":{"block":{"type":"workflow_end","id":"e3","fields":{"NAME":"errored"}}}}}}}}}}}}}]}}
                """;
        var result = service.generate(draft(state), commands, false, "");
        assertThat(result.state()).withFailMessage(result.diagnostics().toString()).isEqualTo("ready");
        var definition = new org.jworkflow.dsl.GroovyWorkflowDslCompiler().compile(new org.jworkflow.definition.WorkflowDefinitionText("order-flow.groovy", result.groovy()));
        assertThat(run(definition, true, new java.math.BigDecimal("100.50"))).isEqualTo("large");
        assertThat(run(definition, true, new java.math.BigDecimal("100.49"))).isEqualTo("small");
        assertThat(run(definition, false, new java.math.BigDecimal("500"))).isEqualTo("errored");
    }

    private static String run(org.jworkflow.model.WorkflowDefinition definition, boolean succeed, Object amount) throws Exception {
        try (var engine = org.jworkflow.engine.WorkflowEngine.builder().definition(definition)
                .stepHandler("DoThing", context -> succeed ? org.jworkflow.engine.StepResult.success() : org.jworkflow.engine.StepResult.failure("DECLINED", "declined"))
                .build()) {
            String businessKey = "order-" + amount + "-" + succeed;
            var id = engine.start(definition.name(), businessKey, java.util.Map.of("amount", amount));
            // Core steps run when the instance receives an event; this mirrors a listener-delivered signal.
            engine.signal(id, new org.jworkflow.model.WorkflowSignal("order.requested", businessKey, null, businessKey, java.time.Instant.now(), java.util.Map.of()));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            var snapshot = engine.snapshot(id);
            while (snapshot.status() != org.jworkflow.model.WorkflowStatus.COMPLETED && snapshot.status() != org.jworkflow.model.WorkflowStatus.FAILED && System.nanoTime() < deadline) {
                Thread.sleep(10); snapshot = engine.snapshot(id);
            }
            assertThat(snapshot.status()).withFailMessage("state=%s status=%s succeed=%s amount=%s", snapshot.state(), snapshot.status(), succeed, amount).isEqualTo(org.jworkflow.model.WorkflowStatus.COMPLETED);
            return snapshot.state();
        }
    }

    private static ProjectWorkspace.DraftView draft(String state) {
        return new ProjectWorkspace.DraftView("doc", "Order flow", "order-flow", "1.0.0", "demo",
                "src/main/resources/workflows", 3, "hash", state);
    }

    private static String validState(boolean layoutChange, String literalType, String literal) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var root = mapper.createObjectNode(); var blocks = root.putObject("blocks"); blocks.put("languageVersion", 0);
        var start = blocks.putArray("blocks").addObject(); start.put("type", "workflow_start"); start.put("id", "start");
        start.put("x", layoutChange ? 900 : 10); start.put("y", layoutChange ? 700 : 20); start.putObject("fields").put("START", "first");
        var step = mapper.createObjectNode(); step.put("type", "workflow_step"); step.put("id", "step");
        var stepFields = step.putObject("fields"); stepFields.put("NAME", "first"); stepFields.put("ACTION", "DoThing"); stepFields.put("SUCCESS", "route"); stepFields.put("FAILURE", "failed");
        var branch = mapper.createObjectNode(); branch.put("type", "workflow_branch"); branch.put("id", "branch");
        var branchFields = branch.putObject("fields"); branchFields.put("NAME", "route"); branchFields.put("VARIABLE", "amount"); branchFields.put("OPERATOR", "gte"); branchFields.put("VALUE_TYPE", literalType); branchFields.put("VALUE", literal); branchFields.put("TRUE_TARGET", "done"); branchFields.put("FALSE_TARGET", "failed");
        var done = mapper.createObjectNode(); done.put("type", "workflow_end"); done.put("id", "done"); done.putObject("fields").put("NAME", "done");
        var failed = mapper.createObjectNode(); failed.put("type", "workflow_end"); failed.put("id", "failed"); failed.putObject("fields").put("NAME", "failed");
        done.putObject("next").set("block", failed); branch.putObject("next").set("block", done); step.putObject("next").set("block", branch); start.putObject("inputs").putObject("NODES").set("block", step);
        return mapper.writeValueAsString(root);
    }
}
