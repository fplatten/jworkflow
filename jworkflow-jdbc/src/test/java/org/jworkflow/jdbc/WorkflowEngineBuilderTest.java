package org.jworkflow.jdbc;

import groovy.lang.Closure;
import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

public final class WorkflowEngineBuilderTest {
    public static void main(String[] args) throws Exception {
        createsInMemoryEngineByDefault();
        commandApiReturnsResultsAndHonorsIdempotency();
        eventBusPublishesByTopicAndTaxonomy();
        eventBusIsolatesSubscriberFailures();
        eventStatusAttemptsCaptureFailedListenerDelivery();
        eventStatusRecordingErrorHandlerAppendsAttempts();
        invalidEventTaxonomyIsRejected();
        workflowDefinitionsRequireUniqueNameAndVersionAndSupportSubWorkflowReferences();
        workflowDefinitionValidatorFindsActionableErrors();
        groovyDslCompilerBuildsDefinitionModel();
        groovyDslCompilerBuildsListenerWaitAndPredicateForms();
        groovyDslCompilerRejectsUnsafeConstructs();
        groovyDslCompilerRejectsMaliciousAstShapesWithoutExecution();
        groovyDslCompilerEnforcesResourceLimitsAndSourceLocations();
        branchConditionEvaluatorSupportsComparisonsAndPredicates();
        builderConfigurationCoversSupportedSettings();
        inMemoryEngineStartsRegisteredDefinitionAtStartNode();
        builderLoadsDefinitionsFromSource();
        inMemoryEnginePublishesWorkflowStartedEvent();
        inMemoryEngineExecutesStepHandlerAndAdvancesTransition();
        inMemoryEngineRoutesThroughGateway();
        inMemoryEngineRoutesThroughRegisteredPredicateGateway();
        inMemoryEngineRoutesThroughLoopUntilConditionChanges();
        inMemoryEngineForkJoinWaitsForAllBranchesInAnyOrder();
        inMemoryEngineCallsSubWorkflowAndRoutesSuccess();
        inMemoryEngineCallsSubWorkflowAndRoutesFailure();
        inMemoryEngineSchedulesAndFiresStepTimeout();
        retryFailedStepRetriesCurrentFailedStep();
        workflowCommandsUseTypedFailures();
        filesystemDefinitionSourceLoadsGroovyDefinitions();
        jdbcConfigurationRequiresExplicitDurableMode();
        incompleteJdbcConfigurationFailsFast();
        boundedJdbcSettingsRejectInvalidValues();
        createsJdbcEngineWithDataSource();
        discoversSqliteDriverWhenPresent();
        createsJdbcEngineWithInjectedDriver();
        jdbcEnginePersistsStartedSnapshot();
        jdbcEventStatusRepositoryAppendsAttempt();
        jdbcWorkflowEventRepositoryFindsEvent();
    }

    private static void createsInMemoryEngineByDefault() throws Exception {
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(WorkflowDefinition.of("hello-world", "1", "completed", WorkflowNode.end("completed")))
                .build();
        WorkflowInstanceId instanceId = engine.start("hello-world", "hello-1", Map.of());

        if (!(engine instanceof InMemoryWorkflowEngine)) {
            throw new AssertionError("Expected default engine to be in memory");
        }
        if (engine.snapshot(instanceId).status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected hello-world workflow to complete");
        }
    }

    private static void commandApiReturnsResultsAndHonorsIdempotency() throws Exception {
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(WorkflowDefinition.of("hello-world", "1", "completed", WorkflowNode.end("completed")))
                .build();
        WorkflowCommandMetadata metadata = metadata("start-order-1", "hello-world", null, "order-1");
        StartWorkflowCommand command = new StartWorkflowCommand(
                "hello-world",
                "1",
                "order-1",
                Map.of("customer", "Ada"),
                metadata);

        StartWorkflowResult first = engine.start(command);
        StartWorkflowResult repeated = engine.start(command);

        if (!repeated.idempotentRepeat()) {
            throw new AssertionError("Expected repeated start command to be marked as an idempotent repeat");
        }
        if (!first.workflowInstanceId().equals(repeated.workflowInstanceId())) {
            throw new AssertionError("Expected repeated start command to return the original workflow instance");
        }

        WorkflowSignal signal = new WorkflowSignal(
                "payment.received",
                "correlation-1",
                null,
                "order-1",
                Instant.now(),
                Map.of());
        WorkflowCommandResult signalResult = engine.signal(new SignalWorkflowCommand(
                first.workflowInstanceId(),
                signal,
                metadata("signal-payment-1", null, first.workflowInstanceId(), "order-1")));

        if (signalResult.status() != WorkflowCommandStatus.ACCEPTED) {
            throw new AssertionError("Expected signal command to be accepted");
        }
        if (!signalResult.workflowInstanceId().equals(first.workflowInstanceId())) {
            throw new AssertionError("Expected signal result to reference the target workflow instance");
        }

        try {
            engine.start(new StartWorkflowCommand(
                    "hello-world",
                    "1",
                    "order-2",
                    Map.of("customer", "Grace"),
                    metadata));
            throw new AssertionError("Expected idempotency conflict when reusing a key with a different payload");
        } catch (WorkflowIdempotencyConflictException expected) {
            if (!"idempotency_conflict".equals(expected.errorCode())) {
                throw new AssertionError("Unexpected idempotency conflict message: " + expected.getMessage(), expected);
            }
        }
    }

    private static void eventBusPublishesByTopicAndTaxonomy() {
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        WorkflowEvent event = new WorkflowEvent(
                EventMetadata.named("payment.authorized"),
                EventMessage.json(Map.of("amount", 42)));
        AtomicInteger topicDeliveries = new AtomicInteger();
        PaymentAuthorizedListener listener = new PaymentAuthorizedListener();

        EventSubscription topicSubscription = eventBus.subscribe("workflow.events", delivered -> {
            if (!event.equals(delivered)) {
                throw new AssertionError("Expected event object to be preserved");
            }
            topicDeliveries.incrementAndGet();
        });
        EventSubscription listenerSubscription = eventBus.subscribe(listener);

        eventBus.publish("workflow.events", event);
        eventBus.publish(event);

        if (topicDeliveries.get() != 1) {
            throw new AssertionError("Expected topic subscriber to receive exactly one topic publication");
        }
        if (listener.deliveries() != 1) {
            throw new AssertionError("Expected annotated listener to receive taxonomy publication");
        }
        if (!"application/json".equals(listener.lastEvent().message().contentType())) {
            throw new AssertionError("Expected event message metadata to be preserved");
        }

        topicSubscription.unsubscribe();
        listenerSubscription.unsubscribe();
        eventBus.publish("workflow.events", event);
        eventBus.publish(event);

        if (topicDeliveries.get() != 1 || listener.deliveries() != 1) {
            throw new AssertionError("Expected unsubscribed handlers to stop receiving events");
        }
    }

    private static void eventBusIsolatesSubscriberFailures() {
        List<EventDeliveryFailure> failures = new ArrayList<>();
        InMemoryEventBus eventBus = InMemoryEventBus.create(failures::add);
        AtomicInteger successfulDeliveries = new AtomicInteger();

        eventBus.subscribe("workflow.events", event -> {
            throw new IllegalStateException("boom");
        });
        eventBus.subscribe("workflow.events", event -> successfulDeliveries.incrementAndGet());
        eventBus.publish("workflow.events", WorkflowEvent.of("workflow.started"));

        if (failures.size() != 1) {
            throw new AssertionError("Expected one subscriber failure to be captured");
        }
        if (successfulDeliveries.get() != 1) {
            throw new AssertionError("Expected failing subscriber not to block later subscribers");
        }
    }

    private static void eventStatusAttemptsCaptureFailedListenerDelivery() {
        WorkflowEvent event = WorkflowEvent.of("payment.failed");
        EventStatusAttempt attempt = EventStatusAttempt.listenerFailure(
                event,
                "payment-listener",
                2,
                new IllegalStateException("declined"));

        if (attempt.status() != EventStatusValue.FAILED) {
            throw new AssertionError("Expected listener failure status");
        }
        if (attempt.scope() != EventStatusScope.LISTENER) {
            throw new AssertionError("Expected listener failure scope");
        }
        if (attempt.retryCount() != 1 || !attempt.retryEligible()) {
            throw new AssertionError("Expected second failed attempt to be retry eligible with retry count 1");
        }
        if (!event.metadata().eventId().equals(attempt.eventId())) {
            throw new AssertionError("Expected event status attempt to reference immutable event ID");
        }
    }

    private static void eventStatusRecordingErrorHandlerAppendsAttempts() {
        InMemoryEventStatusRepository repository = new InMemoryEventStatusRepository();
        InMemoryEventBus eventBus = InMemoryEventBus.create(new EventStatusRecordingErrorHandler(repository));
        WorkflowEvent event = WorkflowEvent.of("payment.failed");

        eventBus.subscribe("workflow.events", delivered -> {
            throw new IllegalStateException("still failing");
        });
        eventBus.publish("workflow.events", event);
        eventBus.publish("workflow.events", event);

        List<EventStatusAttempt> attempts = repository.findAttempts(event.metadata().eventId());
        if (attempts.size() != 2) {
            throw new AssertionError("Expected failed delivery attempts to be appended");
        }
        if (attempts.get(0).attemptNumber() != 1 || attempts.get(1).attemptNumber() != 2) {
            throw new AssertionError("Expected monotonically increasing attempt numbers");
        }
    }

    private static void invalidEventTaxonomyIsRejected() {
        try {
            WorkflowEvent.of("reserveInventory");
            throw new AssertionError("Expected imperative event name to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        try {
            InMemoryEventBus.createDefault().subscribe(new InvalidListener());
            throw new AssertionError("Expected invalid @SubscribeTo taxonomy to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void workflowDefinitionsRequireUniqueNameAndVersionAndSupportSubWorkflowReferences() {
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        WorkflowDefinition payment = WorkflowDefinition.of(
                "payment-flow",
                "1.0.0",
                "completed",
                WorkflowNode.end("completed"));
        SubWorkflowDefinition paymentCall = new SubWorkflowDefinition(
                "payment-flow",
                "1.0.0",
                Map.of("amountDue", "amount"),
                new EventName("payment.collected"),
                new EventName("payment.failed"),
                "prepareShipment",
                "paymentFailed");
        WorkflowDefinition order = WorkflowDefinition.of(
                "order-fulfillment",
                "1.0.0",
                "collectPayment",
                WorkflowNode.subWorkflow("collectPayment", paymentCall),
                WorkflowNode.end("prepareShipment"),
                WorkflowNode.end("paymentFailed"));

        registry.register(payment);
        registry.register(order);

        if (registry.require("order-fulfillment", "1.0.0")
                .nodes()
                .get("collectPayment")
                .subWorkflow() == null) {
            throw new AssertionError("Expected order workflow to include a sub-workflow reference");
        }
        if (registry.find("payment-flow", "1.0.0").isEmpty()) {
            throw new AssertionError("Expected sub-workflow target to be resolvable");
        }

        try {
            registry.register(order);
            throw new AssertionError("Expected duplicate workflow name/version registration to fail");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void workflowDefinitionValidatorFindsActionableErrors() {
        DefinitionValidator validator = new DefinitionValidator();
        WorkflowDefinition valid = WorkflowDefinition.of(
                "simple-flow",
                "1.0.0",
                "created",
                WorkflowNode.step("created", "order.create", List.of(WorkflowTransition.goTo("completed"))),
                WorkflowNode.end("completed"));

        if (!validator.validate(valid).valid()) {
            throw new AssertionError("Expected simple workflow definition to be valid");
        }

        WorkflowDefinition invalid = WorkflowDefinition.of(
                "broken-flow",
                "1.0.0",
                "created",
                WorkflowNode.step("created", "order.create", List.of(WorkflowTransition.goTo("missing"))));
        DefinitionValidationResult result = validator.validate(invalid);

        if (result.valid()) {
            throw new AssertionError("Expected broken workflow definition to be invalid");
        }
        if (result.errors().stream().noneMatch(error -> "transition.target.unknown".equals(error.code()))) {
            throw new AssertionError("Expected missing transition target validation error");
        }
        if (result.errors().stream().noneMatch(error -> "terminal.required".equals(error.code()))) {
            throw new AssertionError("Expected terminal node validation error");
        }
    }

    private static void groovyDslCompilerBuildsDefinitionModel() {
        String dsl = """
                workflow("order-fulfillment") {
                    version "1.0.0"
                    start at: "created"

                    step("created") {
                        action "order.create"
                        timeout "PT2M", goTo: "timedOut"
                        onSuccess goTo: "paymentRoute"
                    }

                    subWorkflow("collectPayment") {
                        workflow "payment-flow", version: "1.0.0"
                        input variable: "orderId", as: "orderId"
                        onSuccess emit: "payment.collected", goTo: "completed"
                        onFailure emit: "payment.failed", goTo: "paymentFailed"
                    }

                    gateway("paymentRoute", type: "exclusive") {
                        when variable: "paymentRequired", eq: true, goTo: "collectPayment"
                        otherwise goTo: "completed"
                    }

                    loop("retryNotification") {
                        whileCondition variable: "notificationSent", eq: false
                        maxIterations 3
                        doStep "sendNotification"
                        then goTo: "completed"
                    }

                    fork("fulfillOrder") {
                        branch "reserveInventory", goTo: "reserveInventory"
                        branch "prepareShipment", goTo: "prepareShipment"

                        join "fulfillmentReady", whenComplete: ["reserveInventory", "prepareShipment"], goTo: "chargePayment"
                    }

                    step("sendNotification") {
                        action "notification.send"
                        onSuccess goTo: "completed"
                    }

                    end("completed")
                    end("paymentFailed")
                    end("timedOut")
                    end("reserveInventory")
                    end("prepareShipment")
                    end("chargePayment")
                }
                """;

        WorkflowDefinition definition = new GroovyWorkflowDslCompiler()
                .compile(new WorkflowDefinitionText("order-fulfillment.groovy", dsl));

        if (!"order-fulfillment".equals(definition.name()) || !"1.0.0".equals(definition.version())) {
            throw new AssertionError("Expected DSL compiler to preserve workflow identity");
        }
        if (definition.nodes().get("created").timeout() == null) {
            throw new AssertionError("Expected step timeout to be compiled");
        }
        if (definition.nodes().get("collectPayment").subWorkflow() == null) {
            throw new AssertionError("Expected sub-workflow to be compiled");
        }
        if (definition.nodes().get("paymentRoute").gatewayType() != GatewayType.EXCLUSIVE) {
            throw new AssertionError("Expected gateway type to be compiled");
        }
        if (definition.nodes().get("retryNotification").loop().maxIterations() != 3) {
            throw new AssertionError("Expected loop max iteration guard to be compiled");
        }
        if (definition.nodes().get("fulfillOrder").fork() == null) {
            throw new AssertionError("Expected fork to be compiled");
        }
        if (definition.nodes().get("fulfillmentReady").join() == null) {
            throw new AssertionError("Expected join to be compiled");
        }
        ListenerInvocation invocation = definition.nodes().get("created").listenerInvocation();
        if (invocation != null && invocation.arguments().stream().anyMatch(Closure.class::isInstance)) {
            throw new AssertionError("Compiled definitions must not retain Groovy closures");
        }
    }

    private static void groovyDslCompilerRejectsUnsafeConstructs() {
        String dsl = """
                import java.io.File

                workflow("unsafe") {
                    version "1.0.0"
                    start at: "created"
                    end("created")
                }
                """;

        try {
            new GroovyWorkflowDslCompiler().compile(new WorkflowDefinitionText("unsafe.groovy", dsl));
            throw new AssertionError("Expected unsafe Groovy construct to be rejected");
        } catch (DslCompilationException expected) {
            if (expected.diagnostics().stream().noneMatch(diagnostic -> diagnostic.category() == DslDiagnosticCategory.SECURITY)) {
                throw new AssertionError("Expected an AST security diagnostic");
            }
        }
    }

    private static void groovyDslCompilerRejectsMaliciousAstShapesWithoutExecution() {
        String sentinel = "jworkflow.dsl.security.sentinel";
        System.clearProperty(sentinel);
        List<String> maliciousBodies = List.of(
                "System.setProperty(\"" + sentinel + "\", \"changed\")",
                "System.getProperties()",
                "Runtime.runtime.exec(\"never-run\")",
                "new ProcessBuilder(\"never-run\")",
                "new URL(\"http://127.0.0.1:1\").text",
                "new File(\"should-not-exist\")",
                "def value = 1",
                "this.getClass().classLoader",
                "Class.forName(\"java.lang.System\")",
                "this.metaClass.danger = { -> true }",
                "System.&exit",
                "[System].*.exit(0)",
                "evaluate(\"System.exit(0)\")",
                "def text = \"${System.getProperty('user.home')}\"",
                "for (item in [1]) { System.setProperty(\"" + sentinel + "\", \"changed\") }",
                "try { throw new RuntimeException() } catch (Exception ignored) { }",
                "return",
                "unknownDslMethod \"ignored\"");

        for (String body : maliciousBodies) {
            String source = """
                    workflow("unsafe") {
                        version "1.0.0"
                        start at: "completed"
                        end("completed")
                        %s
                    }
                    """.formatted(body);
            assertDslRejected(source, "malicious.groovy");
            if (System.getProperty(sentinel) != null) {
                throw new AssertionError("Rejected Groovy source was executed: " + body);
            }
        }

        assertDslRejected("""
                def helper() { System.setProperty("%s", "changed") }
                workflow("method") { version "1"; start at: "done"; end("done") }
                """.formatted(sentinel), "method.groovy");
        assertDslRejected("""
                class Escape { static void run() { System.exit(0) } }
                workflow("class") { version "1"; start at: "done"; end("done") }
                """, "class.groovy");
        assertDslRejected("""
                workflow("first") { version "1"; start at: "done"; end("done") }
                workflow("second") { version "1"; start at: "done"; end("done") }
                """, "multiple.groovy");
        assertDslRejected("""
                workflow("listener-escape") {
                    version "1"
                    start at: "step"
                    step("step") {
                        run { event, context ->
                            context.listener(System.getProperty("listener")).onEvent(event)
                        }
                        then end("done")
                    }
                    end("done")
                }
                """, "listener-escape.groovy");
    }

    private static void groovyDslCompilerEnforcesResourceLimitsAndSourceLocations() {
        DslCompilerOptions limits = new DslCompilerOptions(80, 100, 10, 10, 10, 10, 20, 10);
        try {
            new GroovyWorkflowDslCompiler(limits).compile(new WorkflowDefinitionText(
                    "too-large.groovy",
                    "workflow(\"a-workflow-name-that-makes-this-source-too-large\") { version \"1\"; start at: \"done\"; end(\"done\") }"));
            throw new AssertionError("Expected source limit failure");
        } catch (DslCompilationException expected) {
            DslDiagnostic diagnostic = expected.diagnostics().get(0);
            if (diagnostic.category() != DslDiagnosticCategory.RESOURCE_LIMIT
                    || !"too-large.groovy".equals(diagnostic.position().source())) {
                throw new AssertionError("Expected a source-located resource diagnostic");
            }
        }

        try {
            new GroovyWorkflowDslCompiler().compile(new WorkflowDefinitionText("located.groovy", """
                    workflow("located") {
                        version "1"
                        start at: "done"
                        unknown "value"
                        end("done")
                    }
                    """));
            throw new AssertionError("Expected unknown DSL method failure");
        } catch (DslCompilationException expected) {
            DslDiagnostic diagnostic = expected.diagnostics().get(0);
            if (diagnostic.position().line() != 4 || diagnostic.position().column() < 1) {
                throw new AssertionError("Expected exact source line for DSL diagnostic: " + diagnostic.position());
            }
        }
    }

    private static void groovyDslCompilerBuildsListenerWaitAndPredicateForms() {
        String dsl = """
                workflow("edge-forms") {
                    version "2.0"
                    correlateBy "customerId"
                    start when: "customer.created"
                    step("notify") {
                        on "notification.requested"
                        action "notification.send"
                        retry maxAttempts: 3, backoff: "PT1S"
                        timeout "PT2S"
                        sla "PT1S", onBreach: "warn"
                        run { event, context ->
                            context.listener("audit").record(["Ada", true, 2, null])
                        }
                        onSuccess goTo: "approval"
                        onFailure goTo: "failed"
                    }
                    waitFor("approval") {
                        event "approval.received"
                        correlateBy "customerId"
                        timeout "PT5S", goTo: "failed"
                        then goTo: "route"
                    }
                    gateway("route", type: "inclusive") {
                        when predicate: "eligible", arguments: [minimum: 18, regions: ["US", "CA"]], goTo: "done"
                        otherwise goTo: "failed"
                    }
                    end("done")
                    end("failed")
                }
                """;
        WorkflowDefinition definition = new GroovyWorkflowDslCompiler()
                .compile(new WorkflowDefinitionText("edge-forms.groovy", dsl));
        WorkflowNode notify = definition.nodes().get("notify");
        if (notify.listenerInvocation() == null || notify.retryPolicy() == null || notify.timeout() == null) {
            throw new AssertionError("Expected listener, retry, and timeout declarations");
        }
        Object literal = ((ListenerArgument.Literal) notify.listenerInvocation().arguments().get(0)).value();
        if (!(literal instanceof List<?> list) || !"Ada".equals(list.get(0))) {
            throw new AssertionError("Expected listener list literal metadata");
        }
        if (definition.nodes().get("approval").waitDefinition() == null
                || definition.nodes().get("route").transitions().get(0).condition().predicate() == null) {
            throw new AssertionError("Expected wait and predicate forms");
        }
    }

    private static void assertDslRejected(String source, String location) {
        try {
            new GroovyWorkflowDslCompiler().compile(new WorkflowDefinitionText(location, source));
            throw new AssertionError("Expected DSL source to be rejected: " + location);
        } catch (DslCompilationException expected) {
            if (expected.diagnostics().isEmpty()) {
                throw new AssertionError("Expected a structured DSL diagnostic");
            }
        }
    }

    private static void branchConditionEvaluatorSupportsComparisonsAndPredicates() {
        BranchConditionEvaluator evaluator = new BranchConditionEvaluator()
                .registerPredicate("requiresManualReview", (variables, arguments) ->
                        ((Number) variables.get("score")).intValue() < ((Number) arguments.get("threshold")).intValue());

        if (!evaluator.evaluate(
                new BranchCondition("score", "gte", 700, null, Map.of()),
                Map.of("score", 720))) {
            throw new AssertionError("Expected numeric branch comparison to pass");
        }
        if (!evaluator.evaluate(
                new BranchCondition(null, null, null, "requiresManualReview", Map.of("threshold", 650)),
                Map.of("score", 620))) {
            throw new AssertionError("Expected registered predicate branch to pass");
        }

        try {
            evaluator.evaluate(new BranchCondition("score", "around", 700, null, Map.of()), Map.of("score", 720));
            throw new AssertionError("Expected unsupported branch operator to fail");
        } catch (WorkflowValidationException expected) {
            // Expected.
        }
        Map<String, Object> values = Map.of("number", 10, "text", "beta", "present", true);
        assertCondition(evaluator, new BranchCondition("number", "eq", 10, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("number", "ne", 11, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("number", "neq", 10, null, Map.of()), values, false);
        assertCondition(evaluator, new BranchCondition("number", "gt", 9, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("number", "gte", 10, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("number", "lt", 11, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("number", "lte", 10, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("text", "gt", "alpha", null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("present", "present", null, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("missing", "absent", null, null, Map.of()), values, true);
        assertCondition(evaluator, new BranchCondition("missing", "present", null, null, Map.of()), null, false);
        expectWorkflowValidation(() -> evaluator.evaluate(
                new BranchCondition(null, null, null, "unknown", Map.of()), values));
        expectWorkflowValidation(() -> evaluator.evaluate(
                new BranchCondition("number", "gt", "not-a-number", null, Map.of()), values));
        expectIllegalArgument(() -> new BranchCondition(null, null, null, null, null));
        expectIllegalArgument(() -> evaluator.registerPredicate(" ", (variables, arguments) -> true));
    }

    private static void inMemoryEngineStartsRegisteredDefinitionAtStartNode() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "approval-flow",
                "1.0.0",
                "created",
                WorkflowNode.step("created", "approval.create", List.of(WorkflowTransition.goTo("completed"))),
                WorkflowNode.end("completed"));
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .build();

        StartWorkflowResult result = engine.start(new StartWorkflowCommand(
                "approval-flow",
                "1.0.0",
                "approval-1",
                Map.of(),
                null));

        if (!"created".equals(result.snapshot().state())) {
            throw new AssertionError("Expected engine to start registered workflow at definition start node");
        }
        if (result.snapshot().status() != WorkflowStatus.RUNNING) {
            throw new AssertionError("Expected non-terminal start node to create a running workflow");
        }
    }

    private static void builderLoadsDefinitionsFromSource() throws Exception {
        Path directory = Files.createTempDirectory("jworkflow-builder-definitions");
        Files.writeString(directory.resolve("loaded-flow.groovy"), """
                workflow("loaded-flow") {
                    version "1.0.0"
                    start at: "created"
                    step("created") {
                        action "loaded.create"
                        onSuccess goTo: "completed"
                    }
                    end("completed")
                }
                """);

        WorkflowEngine engine = WorkflowEngine.builder()
                .definitions(new FileSystemWorkflowDefinitionSource(directory))
                .build();
        WorkflowInstanceId instanceId = engine.start("loaded-flow", "loaded-1", Map.of());

        if (!"created".equals(engine.snapshot(instanceId).state())) {
            throw new AssertionError("Expected builder-loaded DSL definition to determine start state");
        }
    }

    private static void inMemoryEnginePublishesWorkflowStartedEvent() throws Exception {
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        List<WorkflowEvent> events = new ArrayList<>();
        eventBus.subscribe("workflow.started", events::add);
        WorkflowEngine engine = WorkflowEngine.builder()
                .eventPublisher(eventBus)
                .definition(WorkflowDefinition.of("hello-world", "1", "completed", WorkflowNode.end("completed")))
                .build();

        StartWorkflowResult result = engine.start(new StartWorkflowCommand(
                "hello-world",
                "1",
                "hello-events",
                Map.of(),
                metadata(null, "hello-world", null, "hello-events")));

        if (events.size() != 1) {
            throw new AssertionError("Expected workflow.started event to be published");
        }
        if (!events.get(0).metadata().workflowInstanceId().equals(result.workflowInstanceId())) {
            throw new AssertionError("Expected started event to include workflow instance ID");
        }
        if (result.emittedEventIds().isEmpty()) {
            throw new AssertionError("Expected start result to include emitted event ID");
        }
    }

    private static void inMemoryEngineExecutesStepHandlerAndAdvancesTransition() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "step-flow",
                "1.0.0",
                "created",
                WorkflowNode.step("created", "order.create", List.of(WorkflowTransition.goTo("completed"))),
                WorkflowNode.end("completed"));
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        List<WorkflowEvent> lifecycleEvents = new ArrayList<>();
        eventBus.subscribe("step.completed", lifecycleEvents::add);
        eventBus.subscribe("transition.taken", lifecycleEvents::add);
        eventBus.subscribe("workflow.completed", lifecycleEvents::add);
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .eventPublisher(eventBus)
                .stepHandler("order.create", context -> StepResult.success(Map.of("handled", true)))
                .build();

        WorkflowInstanceId instanceId = engine.start("step-flow", "step-1", Map.of());
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-step-1",
                null,
                "step-1",
                Instant.now(),
                Map.of()));
        WorkflowSnapshot snapshot = engine.snapshot(instanceId);

        if (!"completed".equals(snapshot.state())) {
            throw new AssertionError("Expected step handler success to advance to completed state");
        }
        if (snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected terminal transition to complete workflow");
        }
        if (!Boolean.TRUE.equals(snapshot.variables().get("handled"))) {
            throw new AssertionError("Expected step handler variables to be merged into snapshot");
        }
        if (lifecycleEvents.size() != 3) {
            throw new AssertionError("Expected step, transition, and workflow lifecycle events");
        }
    }

    private static void inMemoryEngineRoutesThroughGateway() throws Exception {
        String dsl = """
                workflow("gateway-flow") {
                    version "1.0.0"
                    start at: "created"
                    step("created") {
                        action "score.create"
                        onSuccess goTo: "scoreRoute"
                    }
                    gateway("scoreRoute", type: "exclusive") {
                        when variable: "score", gte: 700, goTo: "approved"
                        otherwise goTo: "review"
                    }
                    end("approved")
                    end("review")
                }
                """;
        WorkflowDefinition definition = new GroovyWorkflowDslCompiler()
                .compile(new WorkflowDefinitionText("gateway-flow.groovy", dsl));
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .stepHandler("score.create", context -> StepResult.success(Map.of("score", 725)))
                .build();

        WorkflowInstanceId instanceId = engine.start("gateway-flow", "gateway-1", Map.of());
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-gateway-1",
                null,
                "gateway-1",
                Instant.now(),
                Map.of()));

        WorkflowSnapshot snapshot = engine.snapshot(instanceId);
        if (!"approved".equals(snapshot.state())) {
            throw new AssertionError("Expected gateway to route to approved");
        }
        if (snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected terminal gateway target to complete workflow");
        }
    }

    private static void inMemoryEngineRoutesThroughRegisteredPredicateGateway() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "predicate-flow",
                "1.0.0",
                "created",
                WorkflowNode.step("created", "score.create", List.of(WorkflowTransition.goTo("scoreRoute"))),
                new WorkflowNode(
                        "scoreRoute",
                        WorkflowNodeType.GATEWAY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        GatewayType.EXCLUSIVE,
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                new WorkflowTransition(
                                        null,
                                        "review",
                                        new BranchCondition(null, null, null, "requiresManualReview", Map.of("threshold", 650)),
                                        null),
                                WorkflowTransition.goTo("approved"))),
                WorkflowNode.end("review"),
                WorkflowNode.end("approved"));
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .branchPredicate("requiresManualReview", (variables, arguments) ->
                        ((Number) variables.get("score")).intValue() < ((Number) arguments.get("threshold")).intValue())
                .stepHandler("score.create", context -> StepResult.success(Map.of("score", 600)))
                .build();

        WorkflowInstanceId instanceId = engine.start("predicate-flow", "predicate-1", Map.of());
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-predicate-1",
                null,
                "predicate-1",
                Instant.now(),
                Map.of()));

        if (!"review".equals(engine.snapshot(instanceId).state())) {
            throw new AssertionError("Expected registered predicate gateway to route to review");
        }
    }

    private static void inMemoryEngineRoutesThroughLoopUntilConditionChanges() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "loop-flow",
                "1.0.0",
                "retryNotification",
                new WorkflowNode(
                        "retryNotification",
                        WorkflowNodeType.LOOP,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new LoopDefinition(
                                new BranchCondition("notificationSent", "eq", false, null, Map.of()),
                                3,
                                "sendNotification",
                                "completed"),
                        null,
                        null,
                        null,
                        List.of()),
                WorkflowNode.step("sendNotification", "notification.send", List.of(WorkflowTransition.goTo("retryNotification"))),
                WorkflowNode.end("completed"));
        AtomicInteger attempts = new AtomicInteger();
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .stepHandler("notification.send", context -> {
                    int attempt = attempts.incrementAndGet();
                    return StepResult.success(Map.of("notificationSent", attempt >= 2));
                })
                .build();

        WorkflowInstanceId instanceId = engine.start(
                "loop-flow",
                "loop-1",
                Map.of("notificationSent", false));

        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-loop-1",
                null,
                "loop-1",
                Instant.now(),
                Map.of()));
        if (!"sendNotification".equals(engine.snapshot(instanceId).state())) {
            throw new AssertionError("Expected first loop route to enter loop step");
        }

        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-loop-1",
                null,
                "loop-1",
                Instant.now(),
                Map.of()));
        if (!"sendNotification".equals(engine.snapshot(instanceId).state())) {
            throw new AssertionError("Expected loop to repeat while condition remains true");
        }

        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-loop-1",
                null,
                "loop-1",
                Instant.now(),
                Map.of()));
        WorkflowSnapshot snapshot = engine.snapshot(instanceId);
        if (!"completed".equals(snapshot.state()) || snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected loop to exit after condition changes");
        }
        if (attempts.get() != 2) {
            throw new AssertionError("Expected notification step to run twice");
        }
    }

    private static void inMemoryEngineForkJoinWaitsForAllBranchesInAnyOrder() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "fork-flow",
                "1.0.0",
                "fulfillOrder",
                WorkflowNode.fork("fulfillOrder", new ForkDefinition(
                        Map.of(
                                "reserveInventory", "reserveInventory",
                                "prepareShipment", "prepareShipment"),
                        "fulfillmentReady")),
                WorkflowNode.step("reserveInventory", "inventory.reserve", List.of(WorkflowTransition.goTo("fulfillmentReady"))),
                WorkflowNode.step("prepareShipment", "shipment.prepare", List.of(WorkflowTransition.goTo("fulfillmentReady"))),
                WorkflowNode.join("fulfillmentReady", new JoinDefinition(
                        List.of("reserveInventory", "prepareShipment"),
                        "completed",
                        new EventName("fulfillment.completed"))),
                WorkflowNode.end("completed"));
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        List<WorkflowEvent> joinedEvents = new ArrayList<>();
        List<WorkflowEvent> branchEvents = new ArrayList<>();
        eventBus.subscribe("fulfillment.completed", joinedEvents::add);
        eventBus.subscribe("branch.started", branchEvents::add);
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .eventPublisher(eventBus)
                .build();

        WorkflowInstanceId instanceId = engine.start("fork-flow", "fork-1", Map.of());
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-fork-1",
                null,
                "fork-1",
                Instant.now(),
                Map.of()));
        if (!"fulfillmentReady".equals(engine.snapshot(instanceId).state())) {
            throw new AssertionError("Expected fork to route to join node");
        }
        if (branchEvents.size() != 2) {
            throw new AssertionError("Expected fork to dispatch both branches");
        }
        String forkExecutionId = branchEvents.get(0).metadata().headers().get("forkExecutionId");

        engine.signal(instanceId, new WorkflowSignal(
                "branch.completed",
                "correlation-fork-1",
                null,
                "fork-1",
                Instant.now(),
                Map.of("branch", "prepareShipment", "forkExecutionId", forkExecutionId)));
        if (!"fulfillmentReady".equals(engine.snapshot(instanceId).state())) {
            throw new AssertionError("Expected join to wait for remaining branch");
        }

        engine.signal(instanceId, new WorkflowSignal(
                "branch.completed",
                "correlation-fork-1",
                null,
                "fork-1",
                Instant.now(),
                Map.of("branch", "reserveInventory", "forkExecutionId", forkExecutionId)));
        WorkflowSnapshot snapshot = engine.snapshot(instanceId);
        if (!"completed".equals(snapshot.state()) || snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected join to advance after all branches complete");
        }
        if (joinedEvents.size() != 1) {
            throw new AssertionError("Expected join emitted event exactly once");
        }
    }

    private static void inMemoryEngineCallsSubWorkflowAndRoutesSuccess() throws Exception {
        WorkflowDefinition child = WorkflowDefinition.of(
                "payment-flow",
                "1.0.0",
                "completed",
                WorkflowNode.end("completed"));
        WorkflowDefinition parent = WorkflowDefinition.of(
                "parent-flow",
                "1.0.0",
                "collectPayment",
                WorkflowNode.subWorkflow("collectPayment", new SubWorkflowDefinition(
                        "payment-flow",
                        "1.0.0",
                        Map.of("amountDue", "amount"),
                        new EventName("payment.collected"),
                        new EventName("payment.failed"),
                        "completed",
                        "paymentFailed")),
                WorkflowNode.end("completed"),
                WorkflowNode.end("paymentFailed"));
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        List<WorkflowEvent> collected = new ArrayList<>();
        eventBus.subscribe("payment.collected", collected::add);
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(child)
                .definition(parent)
                .eventPublisher(eventBus)
                .build();

        WorkflowInstanceId instanceId = engine.start("parent-flow", "parent-1", Map.of("amountDue", 25));
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-parent-1",
                null,
                "parent-1",
                Instant.now(),
                Map.of()));

        WorkflowSnapshot snapshot = engine.snapshot(instanceId);
        if (!"completed".equals(snapshot.state()) || snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected successful sub-workflow to route parent to completed");
        }
        if (collected.size() != 1) {
            throw new AssertionError("Expected configured sub-workflow success event");
        }
        if (snapshot.variables().keySet().stream().noneMatch(key -> key.contains("subWorkflow.collectPayment.instanceId"))) {
            throw new AssertionError("Expected parent snapshot to capture child workflow instance ID");
        }
    }

    private static void inMemoryEngineCallsSubWorkflowAndRoutesFailure() throws Exception {
        WorkflowDefinition child = WorkflowDefinition.of(
                "failing-payment-flow",
                "1.0.0",
                "charge",
                WorkflowNode.step("charge", "payment.charge", List.of(WorkflowTransition.goTo("completed"))),
                WorkflowNode.end("completed"));
        WorkflowDefinition parent = WorkflowDefinition.of(
                "parent-failure-flow",
                "1.0.0",
                "collectPayment",
                WorkflowNode.subWorkflow("collectPayment", new SubWorkflowDefinition(
                        "failing-payment-flow",
                        "1.0.0",
                        Map.of(),
                        new EventName("payment.collected"),
                        new EventName("payment.failed"),
                        "completed",
                        "paymentFailed")),
                WorkflowNode.end("completed"),
                WorkflowNode.end("paymentFailed"));
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        List<WorkflowEvent> failed = new ArrayList<>();
        eventBus.subscribe("payment.failed", failed::add);
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(child)
                .definition(parent)
                .eventPublisher(eventBus)
                .stepHandler("payment.charge", context -> {
                    throw new IllegalStateException("declined");
                })
                .build();

        WorkflowInstanceId instanceId = engine.start("parent-failure-flow", "parent-failure-1", Map.of());
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-parent-failure-1",
                null,
                "parent-failure-1",
                Instant.now(),
                Map.of()));

        WorkflowSnapshot snapshot = engine.snapshot(instanceId);
        if (!"paymentFailed".equals(snapshot.state())) {
            throw new AssertionError("Expected parent to eventually reach terminal failure route");
        }
        if (!WorkflowStatus.COMPLETED.equals(snapshot.status())) {
            throw new AssertionError("Expected parent failure route terminal node to complete parent workflow");
        }
        if (failed.size() != 1) {
            throw new AssertionError("Expected configured sub-workflow failure event");
        }
    }

    private static void inMemoryEngineSchedulesAndFiresStepTimeout() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "timeout-flow",
                "1.0.0",
                "waiting",
                new WorkflowNode(
                        "waiting",
                        WorkflowNodeType.STEP,
                        "wait.for.event",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new TimeoutDefinition(java.time.Duration.ofSeconds(5), "timedOut", null),
                        List.of(WorkflowTransition.goTo("completed"))),
                WorkflowNode.end("completed"),
                WorkflowNode.end("timedOut"));
        WorkflowEngine workflowEngine = WorkflowEngine.builder()
                .definition(definition)
                .build();
        WorkflowInstanceId instanceId = workflowEngine.start("timeout-flow", "timeout-1", Map.of());
        InMemoryWorkflowEngine engine = (InMemoryWorkflowEngine) workflowEngine;

        if (engine.pendingTimers().size() != 1) {
            throw new AssertionError("Expected timeout-enabled step to schedule one pending timer");
        }
        WorkflowTimer timer = engine.pendingTimers().get(0);
        List<WorkflowTimer> fired = engine.fireDueTimers(timer.dueAt().plusSeconds(1));

        if (fired.size() != 1) {
            throw new AssertionError("Expected due timeout timer to fire");
        }
        WorkflowSnapshot snapshot = engine.snapshot(instanceId);
        if (!"timedOut".equals(snapshot.state())) {
            throw new AssertionError("Expected timeout to route workflow to timeout target");
        }
        if (snapshot.status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected timeout terminal target to complete workflow");
        }
    }

    private static void retryFailedStepRetriesCurrentFailedStep() throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.of(
                "retry-flow",
                "1.0.0",
                "created",
                WorkflowNode.step("created", "retry.create", List.of(WorkflowTransition.goTo("completed"))),
                WorkflowNode.end("completed"));
        AtomicInteger attempts = new AtomicInteger();
        InMemoryEventBus eventBus = InMemoryEventBus.createDefault();
        List<WorkflowEvent> retryEvents = new ArrayList<>();
        eventBus.subscribe("retry.scheduled", retryEvents::add);
        WorkflowEngine engine = WorkflowEngine.builder()
                .definition(definition)
                .eventPublisher(eventBus)
                .stepHandler("retry.create", context -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    return StepResult.success(Map.of("retried", true));
                })
                .build();

        WorkflowInstanceId instanceId = engine.start("retry-flow", "retry-1", Map.of());
        engine.signal(instanceId, new WorkflowSignal(
                "external_event.received",
                "correlation-retry-1",
                null,
                "retry-1",
                Instant.now(),
                Map.of()));
        if (engine.snapshot(instanceId).status() != WorkflowStatus.FAILED) {
            throw new AssertionError("Expected first step attempt to fail");
        }

        WorkflowCommandResult result = engine.retryFailedStep(new RetryFailedStepCommand(
                instanceId,
                "created",
                metadata("retry-created-1", null, instanceId, "retry-1")));

        if (result.snapshot().status() != WorkflowStatus.COMPLETED) {
            throw new AssertionError("Expected retry to complete workflow");
        }
        if (!Boolean.TRUE.equals(result.snapshot().variables().get("retried"))) {
            throw new AssertionError("Expected retry result variables to be merged");
        }
        if (retryEvents.size() != 1) {
            throw new AssertionError("Expected retry.scheduled event to be published");
        }
    }

    private static void workflowCommandsUseTypedFailures() throws Exception {
        WorkflowEngine engine = WorkflowEngine.builder().build();
        WorkflowInstanceId missingInstanceId = WorkflowInstanceId.random();

        try {
            engine.snapshot(missingInstanceId);
            throw new AssertionError("Expected missing workflow instance to fail with typed exception");
        } catch (WorkflowInstanceNotFoundException expected) {
            if (!"instance_not_found".equals(expected.errorCode())) {
                throw new AssertionError("Unexpected missing instance error code: " + expected.errorCode());
            }
        }

        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        try {
            registry.require("missing-flow", "1.0.0");
            throw new AssertionError("Expected missing workflow definition to fail with typed exception");
        } catch (WorkflowDefinitionNotFoundException expected) {
            if (!"definition_not_found".equals(expected.errorCode())) {
                throw new AssertionError("Unexpected missing definition error code: " + expected.errorCode());
            }
        }
    }

    private static void filesystemDefinitionSourceLoadsGroovyDefinitions() throws Exception {
        Path directory = Files.createTempDirectory("jworkflow-definitions");
        Path definition = directory.resolve("order-flow.groovy");
        Files.writeString(definition, "workflow(\"order-flow\") { version \"1.0.0\" }");

        List<WorkflowDefinitionText> definitions = new FileSystemWorkflowDefinitionSource(directory).load();

        if (definitions.size() != 1) {
            throw new AssertionError("Expected one Groovy workflow definition to be loaded");
        }
        if (!definitions.get(0).location().endsWith("order-flow.groovy")) {
            throw new AssertionError("Expected loaded definition location to include source file name");
        }
        if (!definitions.get(0).content().contains("order-flow")) {
            throw new AssertionError("Expected loaded definition content to be preserved");
        }
    }

    private static void discoversSqliteDriverWhenPresent() throws Exception {
        Files.createDirectories(Path.of("target"));
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.SQLITE)
                .jdbcUrl("jdbc:sqlite:target/jworkflow-test.sqlite")
                .initialize(true)
                .build()) {
            if (!(engine instanceof JdbcWorkflowEngine)) {
                throw new AssertionError("Expected JDBC 4 SQLite driver discovery to build a JDBC engine");
            }
        }
    }

    private static void assertCondition(BranchConditionEvaluator evaluator, BranchCondition condition,
            Map<String, Object> variables, boolean expected) {
        if (evaluator.evaluate(condition, variables) != expected) {
            throw new AssertionError("Unexpected branch result for " + condition.operator());
        }
    }

    private static void expectWorkflowValidation(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected workflow validation failure");
        } catch (WorkflowValidationException expected) {
            // expected
        }
    }

    private static void builderConfigurationCoversSupportedSettings() {
        WorkflowEngineBuilder builder = WorkflowEngine.builder()
                .clock(java.time.Clock.systemUTC())
                .timerPolling(false)
                .timerPollIntervalMillis(10)
                .recoveryLeaseMillis(100)
                .timerBatchSize(1)
                .timerRetryDelayMillis(10)
                .startupValidationBatchSize(1)
                .eventRoutingMaximumCandidates(1)
                .lazyDefinitionValidation(true)
                .inboxPollingMillis(10)
                .inboxClaimLeaseMillis(100)
                .inboxBatchSize(1)
                .inboxRetry(1, 10, 10)
                .outboxPollingMillis(10)
                .outboxClaimLeaseMillis(100)
                .outboxBatchSize(1)
                .outboxRetry(1, 10, 10)
                .username("user")
                .password("password")
                .initialize(false)
                .sqliteBusyTimeoutMillis(0)
                .sqliteWalEnabled(false)
                .dslCompilerOptions(DslCompilerOptions.DEFAULT)
                .eventPublisher(NoOpEventPublisher.INSTANCE)
                .eventCapturePolicy(org.jworkflow.security.CaptureAllEventPolicy.INSTANCE)
                .listener(new Object())
                .listener("listener", new Object())
                .stepHandler("action", context -> StepResult.success())
                .startWorkflowOn("order.created", "orders")
                .branchPredicate("always", (variables, arguments) -> true)
                .setting("custom", "value");

        Properties properties = new Properties();
        properties.setProperty("jworkflow.engine.type", "in-memory");
        properties.setProperty("jworkflow.jdbc.username", "configured-user");
        properties.setProperty("jworkflow.jdbc.password", "configured-password");
        properties.setProperty("jworkflow.schema.initialize", "false");
        properties.setProperty("jworkflow.sqlite.busy-timeout-ms", "1");
        properties.setProperty("jworkflow.sqlite.wal-enabled", "true");
        properties.setProperty("jworkflow.setting.routing.maximum-candidates", "20");
        builder.properties(properties);

        WorkflowEngineProperties typed = new WorkflowEngineProperties(
                WorkflowEngine.Type.IN_MEMORY, null, null, null, null, null, false, Map.of("typed", "true"));
        builder.properties(typed);

        expectIllegalArgument(() -> WorkflowEngine.builder().timerPollIntervalMillis(9));
        expectIllegalArgument(() -> WorkflowEngine.builder().timerPollIntervalMillis(60_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().recoveryLeaseMillis(99));
        expectIllegalArgument(() -> WorkflowEngine.builder().recoveryLeaseMillis(3_600_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().timerRetryDelayMillis(9));
        expectIllegalArgument(() -> WorkflowEngine.builder().startupValidationBatchSize(10_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().eventRoutingMaximumCandidates(0));
        expectIllegalArgument(() -> WorkflowEngine.builder().inboxPollingMillis(60_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().inboxBatchSize(0));
        expectIllegalArgument(() -> WorkflowEngine.builder().inboxRetry(101, 10, 10));
        expectIllegalArgument(() -> WorkflowEngine.builder().outboxPollingMillis(9));
        expectIllegalArgument(() -> WorkflowEngine.builder().outboxClaimLeaseMillis(3_600_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().sqliteBusyTimeoutMillis(-1));
        expectIllegalArgument(() -> WorkflowEngine.builder().stepHandler(" ", context -> StepResult.success()));
        expectIllegalArgument(() -> WorkflowEngine.builder().startWorkflowOn(" ", "orders"));
        expectIllegalArgument(() -> WorkflowEngine.builder().startWorkflowOn("order.created", " "));
        expectIllegalArgument(() -> WorkflowEngine.builder().listener(" ", new Object()));
    }

    private static void jdbcConfigurationRequiresExplicitDurableMode() throws Exception {
        try {
            WorkflowEngine.builder().jdbcUrl("jdbc:sqlite::memory:").build();
            throw new AssertionError("Expected JDBC configuration without explicit SQLITE mode to fail");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("explicit SQLITE")) throw expected;
        }
    }

    private static void incompleteJdbcConfigurationFailsFast() throws Exception {
        try {
            WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).build();
            throw new AssertionError("Expected durable mode without a URL or DataSource to fail");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("jdbcUrl or dataSource")) throw expected;
        }
    }

    private static void boundedJdbcSettingsRejectInvalidValues() {
        expectIllegalArgument(() -> WorkflowEngine.builder().sqliteBusyTimeoutMillis(600_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().timerBatchSize(0));
        expectIllegalArgument(() -> WorkflowEngine.builder().inboxClaimLeaseMillis(99));
        expectIllegalArgument(() -> WorkflowEngine.builder().outboxBatchSize(1_001));
        expectIllegalArgument(() -> WorkflowEngine.builder().outboxRetry(3, 2_000, 1_000));
    }

    private static void createsJdbcEngineWithDataSource() throws Exception {
        Path database = Files.createTempFile("jworkflow-builder-datasource-", ".sqlite");
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.SQLITE)
                .dataSource(source)
                .initialize(true)
                .timerPolling(false)
                .build()) {
            if (!(engine instanceof JdbcWorkflowEngine jdbc) || jdbc.connectionFactory().dataSource() != source) {
                throw new AssertionError("Expected durable builder to use the supplied DataSource");
            }
        }
    }

    private static void expectIllegalArgument(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected bounded setting to reject an invalid value");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void createsJdbcEngineWithInjectedDriver() throws Exception {
        CapturingDriver driver = new CapturingDriver();
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.POSTGRESQL)
                .jdbcUrl("jdbc:capture:jworkflow")
                .driver(driver)
                .build()) {
            if (!(engine instanceof JdbcWorkflowEngine jdbcWorkflowEngine)) {
                throw new AssertionError("Expected injected driver to create a JDBC workflow engine");
            }
            if (jdbcWorkflowEngine.connectionFactory().driver() == null) {
                throw new AssertionError("Expected injected driver to be retained");
            }
        }
    }

    private static void jdbcEnginePersistsStartedSnapshot() throws Exception {
        CapturingDriver driver = new CapturingDriver();
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.POSTGRESQL)
                .jdbcUrl("jdbc:capture:jworkflow")
                .driver(driver)
                .definition(WorkflowDefinition.of("jdbc-flow", "1", "completed", WorkflowNode.end("completed")))
                .build()) {
            engine.start("jdbc-flow", "jdbc-1", Map.of("message", "hello"));
            if (driver.statements().stream().noneMatch(sql -> sql.contains("insert into workflow_instance"))) {
                throw new AssertionError("Expected JDBC engine to persist started workflow snapshot");
            }
            if (driver.statements().stream().noneMatch(sql -> sql.contains("insert into workflow_event"))) {
                throw new AssertionError("Expected JDBC engine to append workflow started event");
            }
        }
    }

    private static void jdbcEventStatusRepositoryAppendsAttempt() {
        CapturingDriver driver = new CapturingDriver();
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(
                "jdbc:capture:jworkflow", null, null, driver, null);
        WorkflowEvent event = WorkflowEvent.of("payment.failed");
        EventStatusAttempt attempt = EventStatusAttempt.listenerFailure(
                event,
                "payment-listener",
                1,
                new IllegalStateException("declined"));

        new JdbcEventStatusRepository(connectionFactory).append(attempt);

        if (driver.statements().stream().noneMatch(sql -> sql.contains("insert into event_status"))) {
            throw new AssertionError("Expected JDBC event status repository to append attempts");
        }
    }

    private static void jdbcWorkflowEventRepositoryFindsEvent() {
        UUID eventId = UUID.randomUUID();
        WorkflowInstanceId instanceId = WorkflowInstanceId.random();
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        WorkflowEvent event = new WorkflowEvent(
                new EventMetadata(
                        eventId,
                        new EventName("payment.failed"),
                        "test",
                        "corr-1",
                        "cause-1",
                        "trace-1",
                        instanceId,
                        "order-1",
                        "tenant-1",
                        "1",
                        occurredAt,
                        occurredAt.plusSeconds(1),
                Map.of()),
                EventMessage.json("{\"reason\":\"declined\"}"));
        CapturingDriver driver = new CapturingDriver().withWorkflowEventRow(event);
        JdbcConnectionFactory connectionFactory = new JdbcConnectionFactory(
                "jdbc:capture:events", null, null, driver, null);

        WorkflowEvent found = new JdbcWorkflowEventRepository(connectionFactory)
                .find(eventId)
                .orElseThrow(() -> new AssertionError("Expected workflow event to be found"));

        if (!found.metadata().eventId().equals(eventId)) {
            throw new AssertionError("Expected event id to round trip");
        }
        if (!found.eventName().equals(new EventName("payment.failed"))) {
            throw new AssertionError("Expected event name to round trip");
        }
        if (!instanceId.equals(found.metadata().workflowInstanceId())) {
            throw new AssertionError("Expected workflow instance id to round trip");
        }
        if (!"{\"reason\":\"declined\"}".equals(found.message().payload())) {
            throw new AssertionError("Expected message payload to round trip");
        }
    }

    private static WorkflowCommandMetadata metadata(
            String idempotencyKey,
            String workflowKey,
            WorkflowInstanceId workflowInstanceId,
            String businessKey
    ) {
        return new WorkflowCommandMetadata(
                null,
                idempotencyKey,
                workflowKey,
                "1",
                workflowInstanceId,
                businessKey,
                "correlation-1",
                null,
                null,
                null,
                "test",
                null,
                null,
                Map.of());
    }

    private static final class PaymentAuthorizedListener implements EventListener {
        private final AtomicInteger deliveries = new AtomicInteger();
        private WorkflowEvent lastEvent;

        @SubscribeTo("payment.authorized")
        @Override
        public void onEvent(WorkflowEvent event) {
            lastEvent = event;
            deliveries.incrementAndGet();
        }

        private int deliveries() {
            return deliveries.get();
        }

        private WorkflowEvent lastEvent() {
            return lastEvent;
        }
    }

    private static final class InvalidListener implements EventListener {
        @SubscribeTo("reserveInventory")
        @Override
        public void onEvent(WorkflowEvent event) {
        }
    }

    private static final class InMemoryEventStatusRepository implements EventStatusRepository {
        private final List<EventStatusAttempt> attempts = new ArrayList<>();

        @Override
        public void append(EventStatusAttempt attempt) {
            attempts.add(attempt);
        }

        @Override
        public List<EventStatusAttempt> findAttempts(UUID eventId) {
            return attempts.stream()
                    .filter(attempt -> attempt.eventId().equals(eventId))
                    .toList();
        }
    }

    private static final class StubDriver implements Driver {
        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:test:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private static final class CapturingDriver implements Driver {
        private final List<String> statements = new ArrayList<>();
        private Map<String, String> workflowEventRow = Map.of();

        @Override
        public Connection connect(String url, Properties info) {
            boolean[] autoCommit = {true};
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("getAutoCommit".equals(method.getName())) return autoCommit[0];
                        if ("setAutoCommit".equals(method.getName())) { autoCommit[0]=(boolean)args[0]; return null; }
                        if ("createStatement".equals(method.getName())) return TransactionTestDataSource.validationStatement();
                        if ("getMetaData".equals(method.getName())) {
                            return Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(),
                                    new Class<?>[]{java.sql.DatabaseMetaData.class}, (p, m, a) ->
                                            "getDatabaseProductName".equals(m.getName()) ? "PostgreSQL" : defaultValue(m.getReturnType()));
                        }
                        if ("prepareStatement".equals(method.getName())) {
                            String sql = (String) args[0];
                            statements.add(sql);
                            return Proxy.newProxyInstance(
                                    java.sql.PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        if ("executeUpdate".equals(statementMethod.getName())) {
                                            return 1;
                                        }
                                        if ("executeQuery".equals(statementMethod.getName())) {
                                            if(sql.contains("returning last_sequence")) {
                                                boolean[] read={false};
                                                return Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(p,m,a)->{
                                                    if(m.getName().equals("next")){boolean next=!read[0];read[0]=true;return next;}
                                                    if(m.getName().equals("getLong"))return 1L;
                                                    return defaultValue(m.getReturnType());
                                                });
                                            }
                                            return resultSet();
                                        }
                                        if ("execute".equals(statementMethod.getName())) {
                                            return true;
                                        }
                                        if ("close".equals(statementMethod.getName())) {
                                            return null;
                                        }
                                        if (statementMethod.getName().startsWith("set")) {
                                            return null;
                                        }
                                        return defaultValue(statementMethod.getReturnType());
                                    });
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:capture:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        private List<String> statements() {
            return statements;
        }

        private CapturingDriver withWorkflowEventRow(WorkflowEvent event) {
            EventMetadata metadata = event.metadata();
            EventMessage message = event.message();
            String persistedMessage = new JdbcJsonCodec().write(Map.of(
                    "payload", message.payload(),
                    "attributes", message.attributes()));
            workflowEventRow = Map.ofEntries(
                    Map.entry("id", metadata.eventId().toString()),
                    Map.entry("event_type", metadata.eventName().value()),
                    Map.entry("source_system", metadata.sourceSystem()),
                    Map.entry("correlation_id", metadata.correlationId()),
                    Map.entry("causation_id", metadata.causationId()),
                    Map.entry("trace_id", metadata.traceId()),
                    Map.entry("workflow_instance_id", metadata.workflowInstanceId().toString()),
                    Map.entry("business_key", metadata.businessKey()),
                    Map.entry("tenant_id", metadata.tenantId()),
                    Map.entry("taxonomy_version", metadata.taxonomyVersion()),
                    Map.entry("occurred_at", metadata.occurredAt().toString()),
                    Map.entry("received_at", metadata.receivedAt().toString()),
                    Map.entry("headers", "{}"),
                    Map.entry("message_payload", persistedMessage),
                    Map.entry("message_content_type", message.contentType()),
                    Map.entry("message_schema_name", ""),
                    Map.entry("message_schema_version", ""),
                    Map.entry("message_redaction_status", message.redacted() ? "REDACTED" : "VISIBLE"));
            return this;
        }

        private Object resultSet() {
            boolean[] consumed = new boolean[]{false};
            return Proxy.newProxyInstance(
                    java.sql.ResultSet.class.getClassLoader(),
                    new Class<?>[]{java.sql.ResultSet.class},
                    (resultSetProxy, resultSetMethod, resultSetArgs) -> {
                        if ("next".equals(resultSetMethod.getName())) {
                            if (workflowEventRow.isEmpty() || consumed[0]) {
                                return false;
                            }
                            consumed[0] = true;
                            return true;
                        }
                        if ("getString".equals(resultSetMethod.getName())) {
                            return workflowEventRow.get((String) resultSetArgs[0]);
                        }
                        if ("getBigDecimal".equals(resultSetMethod.getName())) {
                            String value = workflowEventRow.get((String) resultSetArgs[0]);
                            return value == null ? null : PostgresqlInstantCodec.encode(Instant.parse(value));
                        }
                        if ("close".equals(resultSetMethod.getName())) {
                            return null;
                        }
                        return defaultValue(resultSetMethod.getReturnType());
                    });
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        return null;
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
