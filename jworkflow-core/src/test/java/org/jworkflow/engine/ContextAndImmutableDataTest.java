package org.jworkflow.engine;

import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.ImmutableData;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ContextAndImmutableDataTest {
    @Test
    void stepContextValidatesListenerLookupAndExposesBoundEvent() {
        Object listener = new Object();
        WorkflowStepContext stepContext = new WorkflowStepContext(Map.of("listener", listener));
        assertSame(listener, stepContext.listener("listener"));
        assertThrows(IllegalArgumentException.class, () -> stepContext.listener(" "));
        assertThrows(WorkflowInfrastructureException.class, () -> stepContext.listener("missing"));
        assertNull(stepContext.event());

        WorkflowEvent event = WorkflowEvent.of("context.received");
        WorkflowExecutionContext context = new WorkflowExecutionContext(
                WorkflowInstanceId.random(), "flow", "1", "step", event,
                "correlation", "business", "cause", "trace");
        try (WorkflowExecutionContext.Scope ignored = WorkflowExecutionContext.bind(context)) {
            assertSame(event, stepContext.event());
        }
        assertTrue(WorkflowExecutionContext.current().isEmpty());
    }

    @Test
    void contextSnapshotsWrapRunnableAndCallableWithOrWithoutContext() throws Exception {
        AtomicReference<WorkflowExecutionContext> observed = new AtomicReference<>();
        WorkflowExecutionContext context = new WorkflowExecutionContext(
                WorkflowInstanceId.random(), "flow", "1", "step", null,
                null, "business", null, null);
        WorkflowContextSnapshot captured;
        try (WorkflowExecutionContext.Scope ignored = WorkflowExecutionContext.bind(context)) {
            captured = WorkflowExecutionContext.capture();
        }
        captured.wrap(() -> observed.set(WorkflowExecutionContext.current().orElseThrow())).run();
        assertSame(context, observed.get());
        assertEquals("flow", captured.wrap(() -> WorkflowExecutionContext.current().orElseThrow().workflowName()).call());

        WorkflowContextSnapshot empty = WorkflowExecutionContext.capture();
        AtomicReference<Boolean> emptyObserved = new AtomicReference<>(false);
        empty.wrap(() -> emptyObserved.set(WorkflowExecutionContext.current().isEmpty())).run();
        assertTrue(emptyObserved.get());
        assertEquals("plain", empty.wrap(() -> "plain").call());
        assertThrows(NullPointerException.class, () -> empty.wrap((Runnable) null));
        assertThrows(NullPointerException.class, () -> empty.wrap((java.util.concurrent.Callable<?>) null));
    }

    @Test
    void immutableDataCopiesEverySupportedContainerShape() {
        byte[] bytes = {1, 2};
        Object[] array = {"value", List.of(1)};
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("null", null);
        input.put("string", "text");
        input.put("number", 7);
        input.put("boolean", true);
        input.put("character", 'x');
        input.put("enum", Thread.State.NEW);
        input.put("uuid", UUID.randomUUID());
        input.put("time", Instant.parse("2026-09-17T12:00:00Z"));
        input.put("bytes", bytes);
        input.put("map", Map.of("nested", List.of("item")));
        input.put("list", List.of("item"));
        input.put("set", Set.of("item"));
        input.put("array", array);
        Object arbitrary = new Object();
        input.put("arbitrary", arbitrary);

        Map<String, Object> copy = ImmutableData.copyStringObjectMap(input);
        bytes[0] = 9;
        array[0] = "changed";
        assertArrayEquals(new byte[]{1, 2}, (byte[]) copy.get("bytes"));
        assertEquals("value", ((List<?>) copy.get("array")).get(0));
        assertSame(arbitrary, copy.get("arbitrary"));
        assertThrows(UnsupportedOperationException.class, () -> copy.put("new", true));
        assertEquals(Map.of(), ImmutableData.copyStringObjectMap(null));
        assertEquals(Map.of(), ImmutableData.copyStringObjectMap(Map.of()));
    }

    @Test
    void snapshotsAndEnginePropertiesEnforceDurableDefaults() {
        Instant now = Instant.parse("2026-09-17T12:00:00Z");
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                WorkflowInstanceId.random(), "flow", "1", "revision", "business", "correlation",
                "step", WorkflowStatus.RUNNING, null, 0, now, now);
        assertEquals(Map.of(), snapshot.variables());
        assertEquals(2, snapshot.withLockVersion(2).lockVersion());
        assertEquals("correlation", snapshot.correlationIdentity().value());
        WorkflowInstanceId invalidRevisionId = WorkflowInstanceId.random();
        Map<String, Object> emptyVariables = Map.of();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowSnapshot(
                invalidRevisionId, "flow", "1", " ", "business", null,
                "step", WorkflowStatus.RUNNING, emptyVariables, 0, now, now));
        WorkflowInstanceId invalidVersionId = WorkflowInstanceId.random();
        assertThrows(IllegalArgumentException.class, () -> new WorkflowSnapshot(
                invalidVersionId, "flow", "1", "revision", "business", null,
                "step", WorkflowStatus.RUNNING, emptyVariables, -1, now, now));

        WorkflowEngineProperties defaults = new WorkflowEngineProperties(
                null, null, null, null, null, null, false, null);
        assertEquals(WorkflowEngine.Type.IN_MEMORY, defaults.type());
        assertEquals(Map.of(), defaults.settings());
        assertEquals(WorkflowEngine.Type.IN_MEMORY, WorkflowEngineProperties.inMemory().type());
        assertEquals(WorkflowEngine.Type.SQLITE,
                WorkflowEngineProperties.jdbc(WorkflowEngine.Type.SQLITE, "jdbc:sqlite:test", null, null).type());
        assertThrows(NullPointerException.class,
                () -> WorkflowEngineProperties.jdbc(null, "jdbc:sqlite:test", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowEngineProperties.jdbc(WorkflowEngine.Type.IN_MEMORY, null, null, null));
    }
}
