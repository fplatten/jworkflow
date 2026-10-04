package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** JVM half of the JVM/Wasm parity contract; src/test/browser/shared-rules.test.mjs checks the bundled module. */
class SharedValidationRulesTests {
    @Test void javaRulesMatchTheSharedFixture() throws Exception {
        var fixture = JsonMapper.builder().build().readTree(getClass().getResourceAsStream("/wasm/shared-rules-v1.json"));
        assertEquals(WorkflowGenerationService.RULESET_VERSION, fixture.path("rulesetVersion").asText());
        for (var item : fixture.path("cases"))
            assertEquals(item.path("result").asInt(), SharedValidationRules.check(item.path("rule").asInt(), item.path("value").asInt()), item.toString());
    }
}
