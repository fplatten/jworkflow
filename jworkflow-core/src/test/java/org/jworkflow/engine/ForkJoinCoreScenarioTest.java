package org.jworkflow.engine;

import org.jworkflow.events.EventName;
import org.jworkflow.events.InMemoryEventBus;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.model.ForkDefinition;
import org.jworkflow.model.JoinDefinition;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.model.WorkflowSignal;
import org.jworkflow.model.WorkflowStatus;
import org.jworkflow.model.WorkflowTransition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Complex executable scenarios for the in-memory fork/join contract. */
public final class ForkJoinCoreScenarioTest {
    public static void main(String[] args) throws Exception {
        dispatchesEveryBranchAndJoinsOutOfOrder();
        ignoresUnknownStaleAndDuplicateCompletions();
        completesExactlyOnceUnderConcurrentArrivals();
        isolatesParallelWorkflowInstances();
    }

    private static void dispatchesEveryBranchAndJoinsOutOfOrder() throws Exception {
        InMemoryEventBus bus = InMemoryEventBus.createDefault();
        try (WorkflowEngine engine = engine(bus)) {
            List<WorkflowEvent> started = new CopyOnWriteArrayList<>();
            List<WorkflowEvent> joined = new CopyOnWriteArrayList<>();
            bus.subscribe("branch.started", started::add);
            bus.subscribe("fulfillment.joined", joined::add);

            WorkflowInstanceId id = engine.start("complex-fork", "order-1", Map.of());
            await(() -> eventsFor(started, id).size() == 3);
            List<WorkflowEvent> branchEvents = eventsFor(started, id);
            require(branchEvents.stream().map(event -> event.metadata().headers().get("branch")).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("inventory", "payment", "shipment")), "Fork did not dispatch every branch ID");
            require(branchEvents.stream().map(event -> event.metadata().headers().get("target")).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("reserveInventory", "chargePayment", "createShipment")), "Fork dispatched incorrect branch targets");

            String executionId = oneExecutionId(branchEvents);
            complete(engine, id, "shipment", executionId);
            complete(engine, id, "inventory", executionId);
            require("allReady".equals(engine.snapshot(id).state()), "Join advanced before every branch arrived");
            complete(engine, id, "payment", executionId);
            await(() -> joined.size() == 1);
            require(engine.snapshot(id).status() == WorkflowStatus.COMPLETED, "Join did not complete after all branches arrived");
            require(joined.size() == 1, "Join emitted its completion event more than once");
        }
    }

    private static void ignoresUnknownStaleAndDuplicateCompletions() throws Exception {
        InMemoryEventBus bus = InMemoryEventBus.createDefault();
        try (WorkflowEngine engine = engine(bus)) {
            List<WorkflowEvent> started = new CopyOnWriteArrayList<>();
            List<WorkflowEvent> joined = new CopyOnWriteArrayList<>();
            bus.subscribe("branch.started", started::add);
            bus.subscribe("fulfillment.joined", joined::add);
            WorkflowInstanceId id = engine.start("complex-fork", "order-2", Map.of());
            await(() -> eventsFor(started, id).size() == 3);
            String executionId = oneExecutionId(eventsFor(started, id));

            complete(engine, id, "unknown", executionId);
            complete(engine, id, "inventory", "stale-execution");
            complete(engine, id, "inventory", executionId);
            complete(engine, id, "inventory", executionId);
            require("allReady".equals(engine.snapshot(id).state()), "Invalid or duplicate arrival advanced the join");

            complete(engine, id, "payment", executionId);
            complete(engine, id, "shipment", executionId);
            await(() -> joined.size() == 1);
            require(joined.size() == 1, "Duplicate arrivals caused duplicate join completion");
        }
    }

    private static void completesExactlyOnceUnderConcurrentArrivals() throws Exception {
        InMemoryEventBus bus = InMemoryEventBus.createDefault();
        try (WorkflowEngine engine = engine(bus)) {
            List<WorkflowEvent> started = new CopyOnWriteArrayList<>();
            List<WorkflowEvent> joined = new CopyOnWriteArrayList<>();
            bus.subscribe("branch.started", started::add);
            bus.subscribe("fulfillment.joined", joined::add);
            WorkflowInstanceId id = engine.start("complex-fork", "order-3", Map.of());
            await(() -> eventsFor(started, id).size() == 3);
            String executionId = oneExecutionId(eventsFor(started, id));

            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (String branch : List.of("inventory", "payment", "shipment")) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        go.await();
                        complete(engine, id, branch, executionId);
                        return null;
                    }));
                }
                require(ready.await(5, TimeUnit.SECONDS), "Concurrent branch workers did not become ready");
                go.countDown();
                for (java.util.concurrent.Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            await(() -> joined.size() == 1);
            require(engine.snapshot(id).status() == WorkflowStatus.COMPLETED, "Concurrent arrivals did not complete the workflow");
            require(joined.size() == 1, "Concurrent arrivals completed the join more than once");
        }
    }

    private static void isolatesParallelWorkflowInstances() throws Exception {
        InMemoryEventBus bus = InMemoryEventBus.createDefault();
        try (WorkflowEngine engine = engine(bus)) {
            List<WorkflowEvent> started = new CopyOnWriteArrayList<>();
            bus.subscribe("branch.started", started::add);
            WorkflowInstanceId first = engine.start("complex-fork", "order-a", Map.of());
            WorkflowInstanceId second = engine.start("complex-fork", "order-b", Map.of());
            await(() -> eventsFor(started, first).size() == 3 && eventsFor(started, second).size() == 3);
            String firstExecution = oneExecutionId(eventsFor(started, first));
            String secondExecution = oneExecutionId(eventsFor(started, second));
            require(!firstExecution.equals(secondExecution), "Parallel instances reused a fork execution ID");

            complete(engine, first, "inventory", firstExecution);
            complete(engine, second, "payment", secondExecution);
            complete(engine, first, "payment", firstExecution);
            complete(engine, second, "shipment", secondExecution);
            require("allReady".equals(engine.snapshot(first).state()), "First instance advanced from second instance arrivals");
            require("allReady".equals(engine.snapshot(second).state()), "Second instance advanced from first instance arrivals");
            complete(engine, second, "inventory", secondExecution);
            complete(engine, first, "shipment", firstExecution);
            require(engine.snapshot(first).status() == WorkflowStatus.COMPLETED, "First parallel instance did not complete");
            require(engine.snapshot(second).status() == WorkflowStatus.COMPLETED, "Second parallel instance did not complete");
        }
    }

    private static WorkflowEngine engine(InMemoryEventBus bus) throws Exception {
        return WorkflowEngine.builder().definition(definition()).eventPublisher(bus).build();
    }

    private static WorkflowDefinition definition() {
        return WorkflowDefinition.of(
                "complex-fork", "1.0.0", "fulfill",
                WorkflowNode.fork("fulfill", new ForkDefinition(Map.of(
                        "inventory", "reserveInventory",
                        "payment", "chargePayment",
                        "shipment", "createShipment"), "allReady")),
                WorkflowNode.step("reserveInventory", "inventory.reserve", List.of(WorkflowTransition.goTo("allReady"))),
                WorkflowNode.step("chargePayment", "payment.charge", List.of(WorkflowTransition.goTo("allReady"))),
                WorkflowNode.step("createShipment", "shipment.create", List.of(WorkflowTransition.goTo("allReady"))),
                WorkflowNode.join("allReady", new JoinDefinition(
                        List.of("inventory", "payment", "shipment"), "completed", new EventName("fulfillment.joined"))),
                WorkflowNode.end("completed"));
    }

    private static void complete(WorkflowEngine engine, WorkflowInstanceId id, String branch, String executionId) {
        engine.signal(id, new WorkflowSignal("branch.completed", id.toString(), null, null, Instant.now(),
                Map.of("branch", branch, "forkExecutionId", executionId)));
    }

    private static List<WorkflowEvent> eventsFor(List<WorkflowEvent> events, WorkflowInstanceId id) {
        return events.stream().filter(event -> id.equals(event.metadata().workflowInstanceId())).toList();
    }

    private static String oneExecutionId(List<WorkflowEvent> events) {
        Set<String> ids = new HashSet<>();
        events.forEach(event -> ids.add(event.metadata().headers().get("forkExecutionId")));
        require(ids.size() == 1 && !ids.contains(null), "Expected one non-null fork execution ID");
        return ids.iterator().next();
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.satisfied() && System.nanoTime() < deadline) Thread.onSpinWait();
        require(check.satisfied(), "Timed out waiting for asynchronous event delivery");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Check { boolean satisfied() throws Exception; }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
