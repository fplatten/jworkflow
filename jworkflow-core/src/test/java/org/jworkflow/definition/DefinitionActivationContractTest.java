package org.jworkflow.definition;

import org.jworkflow.dsl.GroovyWorkflowDslCompiler;
import org.jworkflow.model.DefinitionValidator;
import org.jworkflow.model.WorkflowDefinitionRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class DefinitionActivationContractTest {
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    public static void main(String[] args) throws Exception {
        filesystemCandidatesActivateWithProvenance();
        rejectedCandidatePreservesLastKnownGood();
        validNewVersionAdvancesSourceAndRetainsPriorVersion();
        changedImmutableIdentityIsRejected();
        identicalDefinitionIsIdempotent();
    }

    private static void filesystemCandidatesActivateWithProvenance() throws Exception {
        Fixture fixture = fixture("initial");
        Files.writeString(fixture.file, dsl("1.0.0", "work.initial", "done"));
        DefinitionActivationResult result = fixture.service.activate(fixture.source()).get(0);
        check(result.status() == DefinitionActivationStatus.ACTIVATED, "valid source must activate");
        check(result.active().workflowVersion().equals("1.0.0"), "activated version must be recorded");
        check(result.active().revision().equals(result.active().definition().checksum()), "revision/checksum must be recorded");
        check(result.source().location().equals(fixture.file.toString()), "filesystem location must be recorded");
        check(result.source().sourceChecksum().length() == 64 && result.source().activatedAt().equals(NOW),
                "source checksum and activation time must be recorded");
    }

    private static void rejectedCandidatePreservesLastKnownGood() throws Exception {
        Fixture fixture = fixture("invalid");
        Files.writeString(fixture.file, dsl("1.0.0", "work.good", "done"));
        ActivatedWorkflowDefinition good = fixture.service.activate(fixture.source()).get(0).active();
        Files.writeString(fixture.file, "workflow(\"activation-flow\") { version \"1.0.0\"; new File(\"escape\") }");
        DefinitionActivationResult rejected = fixture.service.activate(fixture.source()).get(0);
        check(rejected.status() == DefinitionActivationStatus.REJECTED && !rejected.errors().isEmpty(),
                "unsafe/invalid replacement must be rejected with a diagnostic");
        ActivatedWorkflowDefinition active = fixture.registry.activeSource(fixture.file.toString()).orElseThrow();
        check(active.revision().equals(good.revision()), "rejected source must not replace last-known-good revision");
        check(fixture.registry.require("activation-flow", "1.0.0").revision().equals(good.revision()),
                "rejected source must not mutate the definition registry");
    }

    private static void validNewVersionAdvancesSourceAndRetainsPriorVersion() throws Exception {
        Fixture fixture = fixture("upgrade");
        Files.writeString(fixture.file, dsl("1.0.0", "work.one", "done"));
        fixture.service.activate(fixture.source());
        Files.writeString(fixture.file, dsl("2.0.0", "work.two", "finished"));
        DefinitionActivationResult upgraded = fixture.service.activate(fixture.source()).get(0);
        check(upgraded.status() == DefinitionActivationStatus.ACTIVATED, "new semantic version must activate");
        check(fixture.registry.activeSource(fixture.file.toString()).orElseThrow().workflowVersion().equals("2.0.0"),
                "source pointer must advance only after successful activation");
        check(fixture.registry.find("activation-flow", "1.0.0").isPresent()
                        && fixture.registry.find("activation-flow", "2.0.0").isPresent(),
                "prior version must remain available for pinned instances");
    }

    private static void changedImmutableIdentityIsRejected() throws Exception {
        Fixture fixture = fixture("identity");
        Files.writeString(fixture.file, dsl("1.0.0", "work.original", "done"));
        ActivatedWorkflowDefinition original = fixture.service.activate(fixture.source()).get(0).active();
        Files.writeString(fixture.file, dsl("1.0.0", "work.changed", "finished"));
        DefinitionActivationResult rejected = fixture.service.activate(fixture.source()).get(0);
        check(rejected.status() == DefinitionActivationStatus.REJECTED
                        && rejected.errors().get(0).contains("changed content requires a new version"),
                "same name/version with changed content must be rejected");
        check(fixture.registry.activeSource(fixture.file.toString()).orElseThrow().revision().equals(original.revision()),
                "identity conflict must retain last-known-good source activation");
    }

    private static void identicalDefinitionIsIdempotent() throws Exception {
        Fixture fixture = fixture("same");
        Files.writeString(fixture.file, dsl("1.0.0", "work.same", "done"));
        fixture.service.activate(fixture.source());
        DefinitionActivationResult repeated = fixture.service.activate(fixture.source()).get(0);
        check(repeated.status() == DefinitionActivationStatus.UNCHANGED && fixture.registry.snapshot().size() == 1,
                "identical reload must be idempotent");
    }

    private static Fixture fixture(String name) throws Exception {
        Path directory = Files.createTempDirectory("jworkflow-activation-" + name + "-");
        Path file = directory.resolve("workflow.groovy");
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry();
        WorkflowDefinitionActivationService service = new WorkflowDefinitionActivationService(
                registry, new GroovyWorkflowDslCompiler(), new DefinitionValidator(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(file, registry, service);
    }

    private static String dsl(String version, String action, String terminal) {
        return """
                workflow("activation-flow") {
                    version "%s"
                    start at: "start"
                    step("start") {
                        action "%s"
                        onSuccess goTo: "%s"
                    }
                    end("%s")
                }
                """.formatted(version, action, terminal, terminal);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(Path file, WorkflowDefinitionRegistry registry,
                           WorkflowDefinitionActivationService service) {
        WorkflowDefinitionSource source() { return new FileSystemWorkflowDefinitionSource(file.getParent()); }
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
