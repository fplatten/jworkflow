package org.jworkflow.events;

import org.jworkflow.security.*;
import java.time.Instant;
import java.util.*;

public final class EventTaxonomySecurityContractTest {
    public static void main(String[] args) {
        typedIdentitiesRoundTripWithoutBreakingStringAccessors();
        integrationEventsRemainImmutableAndConvertible();
        capturePoliciesRemoveSensitiveDataAndPreserveRoutingIdentity();
    }

    private static void typedIdentitiesRoundTripWithoutBreakingStringAccessors() {
        EventMetadata metadata = EventMetadata.withIdentities(null, new EventName("employee.created"), "hr",
                new CorrelationId("corr-1"), new CausationId("cause-1"), new TraceId("trace-1"),
                null, "employee-1", null, "1", Instant.EPOCH, Instant.EPOCH, Map.of());
        check(metadata.correlationId().equals("corr-1") && metadata.correlationIdentity().value().equals("corr-1"),
                "typed and transitional correlation access must agree");
        check(metadata.causationIdentity().value().equals("cause-1"), "causation identity was not preserved");
        check(metadata.traceIdentity().value().equals("trace-1"), "trace identity was not preserved");
        expectIllegal(() -> new CorrelationId(" "));
        expectIllegal(() -> new TraceId("x".repeat(513)));
    }

    private static void integrationEventsRemainImmutableAndConvertible() {
        LinkedHashMap<String,Object> payload = new LinkedHashMap<>(); payload.put("name", "Ada");
        IntegrationEvent integration = IntegrationEvent.named("employee.created", payload);
        payload.put("name", "changed");
        WorkflowEvent workflow = integration.toWorkflowEvent();
        check(((Map<?,?>) workflow.message().payload()).get("name").equals("Ada"), "payload must be defensively copied");
        check(IntegrationEvent.from(workflow).metadata().eventId().equals(workflow.metadata().eventId()),
                "integration/workflow conversion must preserve identity");
    }

    private static void capturePoliciesRemoveSensitiveDataAndPreserveRoutingIdentity() {
        EventMetadata metadata = new EventMetadata(null, new EventName("tax.submitted"), "payroll", "corr", "cause",
                "trace", null, "employee", null, "1", Instant.EPOCH, Instant.EPOCH,
                Map.of("authorization", "secret", "workflowKey", "onboarding"));
        WorkflowEvent source = new WorkflowEvent(metadata, new EventMessage(Map.of("ssn", "123"), "application/json",
                "tax-form", "1", false, Map.of("token", "secret", "safe", "yes")));
        WorkflowEvent filtered = new FilteringEventCapturePolicy(Set.of("authorization"), Set.of("token"), true).filter(source);
        check(filtered.metadata().eventId().equals(source.metadata().eventId()), "filter must preserve event identity");
        check(filtered.metadata().correlationId().equals("corr"), "filter must preserve routing metadata");
        check(!filtered.metadata().headers().containsKey("authorization") && filtered.metadata().headers().containsKey("workflowKey"),
                "configured headers must be removed selectively");
        check(filtered.message().payload() == null && filtered.message().redacted(), "payload must be redacted");
        check(!filtered.message().attributes().containsKey("token") && filtered.message().attributes().containsKey("safe"),
                "message attributes must be filtered selectively");
        WorkflowEvent metadataOnly = MetadataOnlyEventPolicy.INSTANCE.filter(source);
        check(metadataOnly.message().payload() == null && metadataOnly.message().attributes().isEmpty(),
                "metadata-only policy must suppress message content");
        check(CaptureAllEventPolicy.INSTANCE.filter(source) == source, "capture-all policy must preserve compatibility");
    }

    private static void expectIllegal(Runnable operation) { try { operation.run(); throw new AssertionError("expected validation failure"); } catch (IllegalArgumentException expected) { } }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
