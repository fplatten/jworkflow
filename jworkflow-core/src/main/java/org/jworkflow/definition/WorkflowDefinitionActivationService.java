package org.jworkflow.definition;

import org.jworkflow.dsl.GroovyWorkflowDslCompiler;
import org.jworkflow.model.DefinitionValidationResult;
import org.jworkflow.model.DefinitionValidator;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowDefinitionRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Compile, validate, and atomically activate candidates without displacing last-known-good state on failure. */
public final class WorkflowDefinitionActivationService {
    private final WorkflowDefinitionRegistry registry;
    private final GroovyWorkflowDslCompiler compiler;
    private final DefinitionValidator validator;
    private final Clock clock;

    /**
     * Constructs WorkflowDefinitionActivationService with the supplied collaborators and configuration.
     * @param registry registry retaining activated workflow definitions
     */
    public WorkflowDefinitionActivationService(WorkflowDefinitionRegistry registry) {
        this(registry, new GroovyWorkflowDslCompiler(), new DefinitionValidator(), Clock.systemUTC());
    }

    /**
     * Constructs WorkflowDefinitionActivationService with the supplied collaborators and configuration.
     * @param registry registry retaining activated workflow definitions
     * @param compiler restricted Groovy DSL compiler
     * @param validator workflow graph validator
     * @param clock clock used for recorded times and lease/retry decisions
     * @throws NullPointerException if registry, compiler, validator, clock is null
     */
    public WorkflowDefinitionActivationService(WorkflowDefinitionRegistry registry,
                                               GroovyWorkflowDslCompiler compiler,
                                               DefinitionValidator validator,
                                               Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Compiles and validates candidates, retaining last-known-good registry state for each rejected candidate.
     *  Results report rejection without requiring an exception.
     * @param source source provider to load and activate
     * @return the matching values in the order defined by this operation
     * @throws NullPointerException if source is null
     */
    public List<DefinitionActivationResult> activate(WorkflowDefinitionSource source) {
        Objects.requireNonNull(source, "source");
        List<WorkflowDefinitionText> candidates = source.load();
        ArrayList<DefinitionActivationResult> results = new ArrayList<>(candidates.size());
        for (WorkflowDefinitionText candidate : candidates) results.add(activate(candidate));
        return List.copyOf(results);
    }

    /**
     * Compiles and validates candidates, retaining last-known-good registry state for each rejected candidate.
     * Results report rejection without requiring an exception.
     * @param candidate source candidate to compile and validate
     * @return the resulting definition activation result
     * @throws NullPointerException if candidate is null
     */
    public DefinitionActivationResult activate(WorkflowDefinitionText candidate) {
        Objects.requireNonNull(candidate, "candidate");
        WorkflowDefinitionSourceMetadata metadata = metadata(candidate);
        try {
            WorkflowDefinition definition = compiler.compile(candidate);
            DefinitionValidationResult validation = validator.validate(definition, registry);
            if (!validation.valid()) {
                return rejected(metadata, validation.errors().stream()
                        .map(error -> error.code() + ": " + error.message()).toList());
            }
            WorkflowDefinitionRegistry.Activation activation = registry.activate(definition, metadata);
            return new DefinitionActivationResult(
                    activation.changed() ? DefinitionActivationStatus.ACTIVATED : DefinitionActivationStatus.UNCHANGED,
                    metadata, activation.active(), List.of());
        } catch (RuntimeException failure) {
            return rejected(metadata, List.of(safeMessage(failure)));
        }
    }

    /**
     * Activates loaded candidates and throws a structured activation exception when a candidate is rejected.
     * @param source source provider to load and activate
     * @return the matching values in the order defined by this operation
     */
    public List<DefinitionActivationResult> activateOrThrow(WorkflowDefinitionSource source) {
        List<DefinitionActivationResult> results = activate(source);
        results.stream().filter(result -> !result.successful()).findFirst()
                .ifPresent(result -> { throw new DefinitionActivationException(result, null); });
        return results;
    }

    private WorkflowDefinitionSourceMetadata metadata(WorkflowDefinitionText text) {
        return new WorkflowDefinitionSourceMetadata(text.location(), sha256(text.content()), clock.instant());
    }

    private static DefinitionActivationResult rejected(WorkflowDefinitionSourceMetadata metadata, List<String> errors) {
        return new DefinitionActivationResult(DefinitionActivationStatus.REJECTED, metadata, null, errors);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
