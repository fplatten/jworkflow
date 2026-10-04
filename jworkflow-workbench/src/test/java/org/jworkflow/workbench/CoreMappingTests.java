package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.jworkflow.definition.WorkflowDefinitionBuilder;
import org.jworkflow.engine.StepResult;
import org.jworkflow.model.*;
import org.junit.jupiter.api.Test;

/** Executable evidence for the sequence, two-way branch and failure mappings exposed after M2. */
class CoreMappingTests {
    @Test void mapsSequenceBranchOutcomesAndStepFailureToPinnedCore() {
        WorkflowDefinition definition = WorkflowDefinitionBuilder.workflow("order")
                .version("1").startAt("load")
                .step("load", step -> step.action("order.load").onSuccess("route").onFailure("failed"))
                .branch("route", branch -> branch.when(value -> value.named("priority").variable("priority", "eq", true).goTo("fast")).otherwise("normal"))
                .step("fast", step -> step.action("order.fast").onSuccess("done").onFailure("failed"))
                .step("normal", step -> step.action("order.normal").onSuccess("done").onFailure("failed"))
                .end("done").end("failed").build();
        assertEquals("load", definition.startNode());
        assertEquals("route", target(definition.nodes().get("load"), "success"));
        assertEquals("failed", target(definition.nodes().get("load"), "failure"));
        WorkflowNode route = definition.nodes().get("route"); assertEquals(WorkflowNodeType.GATEWAY, route.type());
        assertEquals("fast", route.transitions().stream().filter(t -> t.condition() != null).findFirst().orElseThrow().targetNode());
        assertEquals("normal", route.transitions().stream().filter(t -> t.condition() == null).findFirst().orElseThrow().targetNode());
        StepResult failure = StepResult.failure("DECLINED", "Order rejected"); assertFalse(failure.successful()); assertEquals(Map.of(), failure.variables());
    }
    private static String target(WorkflowNode node, String event) { return node.transitions().stream().filter(t -> event.equals(t.name())).findFirst().orElseThrow().targetNode(); }
}
