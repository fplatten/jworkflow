package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.model.*;
import org.jworkflow.outbox.OutboxMessage;
import org.jworkflow.security.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class JdbcSecurityCaptureContractTest {
    public static void main(String[] args) throws Exception {
        redactionPrecedesSnapshotEventOutboxInboxObservationAndQueryCapture();
    }

    private static void redactionPrecedesSnapshotEventOutboxInboxObservationAndQueryCapture() throws Exception {
        Path database = Files.createTempFile("jworkflow-security-", ".sqlite");
        List<WorkflowEvent> published = new ArrayList<>();
        WorkflowDefinition definition = WorkflowDefinition.of("secure-onboarding", "1", "tax",
                WorkflowNode.waitFor("tax", new WaitDefinition(new EventName("tax.submitted"), "employeeId", "done"), null),
                WorkflowNode.end("done"));
        try (WorkflowEngine built = WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE)
                .jdbcUrl("jdbc:sqlite:" + database.toAbsolutePath()).initialize(true).timerPolling(false)
                .definition(definition).eventCapturePolicy(MetadataOnlyEventPolicy.INSTANCE)
                .eventPublisher(published::add).build()) {
            JdbcWorkflowEngine engine = (JdbcWorkflowEngine) built;
            var started = engine.start("secure-onboarding", "employee-1", Map.of());
            WorkflowEvent incoming = new WorkflowEvent(new EventMetadata(null, new EventName("tax.submitted"), "payroll",
                    "employee-1", "cause-1", "trace-1", started, "employee-1", null, "1",
                    Instant.now(), Instant.now(), Map.of("authorization", "secret")),
                    new EventMessage(Map.of("ssn", "123-45-6789"), "application/json", "tax-form", "1", false,
                            Map.of("accessToken", "secret")));
            engine.publish(IntegrationEvent.from(incoming));
            check(!engine.snapshot(started).variables().containsKey("ssn"),
                    "redacted ingress payload must not enter durable workflow variables");
            check(published.stream().allMatch(e -> e.message().payload() == null && e.message().redacted()),
                    "published workflow events must be redacted");
            check(engine.queries().timeline(started).entries().stream()
                    .allMatch(e -> e.message().payload() == null && e.message().redacted()),
                    "query timelines must expose only captured redacted events");
            List<OutboxMessage> outbox = new JdbcOutboxRepository(engine.connectionFactory()).findPending(100);
            check(!outbox.isEmpty() && outbox.stream().allMatch(m -> m.message().payload() == null && m.message().redacted()),
                    "outbox must contain only redacted event messages");
            WorkflowEvent captured = engine.capture(incoming);
            new JdbcWorkflowEventRepository(engine.connectionFactory()).append(captured);
            WorkflowEvent reloaded = new JdbcWorkflowEventRepository(engine.connectionFactory())
                    .find(captured.metadata().eventId()).orElseThrow();
            check(reloaded.metadata().correlationIdentity().equals(new CorrelationId("employee-1"))
                            && reloaded.metadata().causationIdentity().equals(new CausationId("cause-1"))
                            && reloaded.metadata().traceIdentity().equals(new TraceId("trace-1")),
                    "typed identities must survive JDBC round-trip");
            check(reloaded.message().redacted() && reloaded.message().payload() == null,
                    "workflow event redaction status must survive JDBC round-trip");
            try (JdbcInboxApplication inbox = engine.inbox(message -> List.of())) {
                JdbcInboxEventAdapter adapter = new JdbcInboxEventAdapter(inbox);
                InboxInsertResult accepted = adapter.onEvent(IntegrationEvent.from(incoming));
                InboxMessage stored = inbox.find(accepted.message().messageId()).orElseThrow();
                check(stored.message().payload() == null && stored.message().redacted(),
                        "inbox persistence must apply the capture policy");
            }
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
