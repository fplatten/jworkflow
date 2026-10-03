package org.jworkflow.model;

import org.jworkflow.engine.WorkflowValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BranchConditionEvaluatorTest {
    private final BranchConditionEvaluator evaluator = new BranchConditionEvaluator();

    @ParameterizedTest
    @CsvSource({"eq,2,true", "eq,3,false", "ne,2,false", "ne,3,true",
            "neq,2,false", "neq,3,true", "gt,1,true", "gt,2,false", "gt,3,false",
            "gte,1,true", "gte,2,true", "gte,3,false", "lt,1,false", "lt,2,false", "lt,3,true",
            "lte,1,false", "lte,2,true", "lte,3,true"})
    void comparisonsRespectEqualityBoundaries(String operator, int expected, boolean result) {
        var condition = new BranchCondition("value", operator, expected, null, Map.of());
        assertEquals(result, evaluator.evaluate(condition, Map.of("value", 2)));
    }

    @Test void numericComparisonPreservesPrecisionAcrossNumberTypes() {
        var condition = new BranchCondition("value", "gt", new BigDecimal("9007199254740992"), null, null);
        assertTrue(evaluator.evaluate(condition, Map.of("value", 9007199254740993L)));
        var equal = new BranchCondition("value", "gte", new BigDecimal("2.00"), null, null);
        assertTrue(evaluator.evaluate(equal, Map.of("value", 2)));
    }

    @Test void absentAndPresentAcceptMissingVariables() {
        var present = new BranchCondition("value", "present", null, null, null);
        var absent = new BranchCondition("value", "absent", null, null, null);
        assertFalse(evaluator.evaluate(present, null));
        assertTrue(evaluator.evaluate(absent, null));
        assertTrue(evaluator.evaluate(present, Map.of("value", "")));
        assertFalse(evaluator.evaluate(absent, Map.of("value", "")));
    }

    @Test void comparableValuesRequireCompatibleTypes() {
        var strings = new BranchCondition("value", "lt", "b", null, null);
        assertTrue(evaluator.evaluate(strings, Map.of("value", "a")));
        Map<String,Object> incompatible = Map.of("value", 1);
        Map<String,Object> missing = Map.of();
        assertThrows(WorkflowValidationException.class, () -> evaluator.evaluate(strings, incompatible));
        assertThrows(WorkflowValidationException.class, () -> evaluator.evaluate(strings, missing));
        var unsupported = new BranchCondition("value", "unknown", "b", null, null);
        assertThrows(WorkflowValidationException.class, () -> evaluator.evaluate(unsupported, missing));
    }

    @Test void predicatesReceiveVariablesAndArgumentsAndMustBeRegistered() {
        var condition = new BranchCondition(null, null, null, "allowed", Map.of("minimum", 5));
        Map<String,Object> variables = Map.of("value", 6);
        assertThrows(WorkflowValidationException.class, () -> evaluator.evaluate(condition, variables));
        assertSame(evaluator, evaluator.registerPredicate("allowed", (values, arguments) ->
                (int) values.get("value") > (int) arguments.get("minimum")));
        assertTrue(evaluator.evaluate(condition, variables));
        assertFalse(evaluator.evaluate(condition, Map.of("value", 5)));
        assertThrows(IllegalArgumentException.class, () -> evaluator.registerPredicate(null, (v, a) -> true));
        assertThrows(IllegalArgumentException.class, () -> evaluator.registerPredicate(" ", (v, a) -> true));
        assertThrows(NullPointerException.class, () -> evaluator.registerPredicate("null", null));
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(null, variables));
    }
}
