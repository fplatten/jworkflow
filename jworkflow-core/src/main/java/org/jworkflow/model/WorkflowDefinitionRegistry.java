package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.engine.*;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of immutable definitions indexed by name/version and their activated source provenance.
 * Changed content requires a new version; existing snapshots retain their persisted revision pins.
 */
public final class WorkflowDefinitionRegistry {
    /** Creates an empty registry with no selected definitions or activated sources. */
    public WorkflowDefinitionRegistry() {
    }

    private final ConcurrentMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActivatedWorkflowDefinition> activeSources = new ConcurrentHashMap<>();

    /**
     * Registers a definition under a previously unused name/version. Validate its graph before calling this
     * method; registration does not run the graph validator and rejects even an identical repeated key.
     * @param definition immutable workflow definition
     * @throws NullPointerException if definition is null
     * @throws IllegalArgumentException if the name/version is already registered
     */
    public void register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        WorkflowDefinition prior = definitions.putIfAbsent(definition.key(), definition);
        if (prior != null) {
            throw new IllegalArgumentException("Workflow definition already registered: " + definition.key());
        }
    }

    /**
     * Looks up the exact registered name/version pair. Use {@link #latest(String)} for version selection.
     * @param workflowName workflow definition name
     * @param workflowVersion nonblank workflow definition version
     * @return the matching value, or an empty optional when absent
     * @throws IllegalArgumentException if either name or version is null or blank
     */
    public Optional<WorkflowDefinition> find(String workflowName, String workflowVersion) {
        return Optional.ofNullable(definitions.get(key(workflowName, workflowVersion)));
    }

    /**
     * Returns the registry's default selection for a workflow name; this is not a semantic-version upgrade
     * guarantee.
     * @param workflowName workflow definition name
     * @return the matching value, or an empty optional when absent
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public Optional<WorkflowDefinition> latest(String workflowName) {
        if (workflowName == null || workflowName.isBlank()) {
            throw new IllegalArgumentException("workflowName is required");
        }
        return definitions.values().stream()
                .filter(definition -> definition.name().equals(workflowName))
                .sorted((left, right) -> compareVersions(right.version(), left.version()))
                .findFirst();
    }

    /**
     * Resolves a definition or fails with a missing-definition exception.
     * @param workflowName workflow definition name
     * @param workflowVersion nonblank workflow definition version
     * @return the immutable workflow definition
     * @throws IllegalArgumentException if either name or version is null or blank
     * @throws WorkflowDefinitionNotFoundException if the exact name/version is not registered
     */
    public WorkflowDefinition require(String workflowName, String workflowVersion) {
        return find(workflowName, workflowVersion)
                .orElseThrow(() -> new WorkflowDefinitionNotFoundException(workflowName, workflowVersion));
    }

    /**
     * Returns an immutable copy of the selected name/version definitions.
     * @return an immutable copy of the selected name/version definitions
     */
    public Map<String, WorkflowDefinition> snapshot() {
        return Map.copyOf(definitions);
    }

    /**
     * Activates one fully compiled and validated candidate. Definition identities are immutable: changed
     *   content must use a new version. Prior versions remain registered for pinned workflow instances.
     * @param definition immutable workflow definition
     * @param source source provenance recorded on successful activation
     * @return the resulting activation
     * @throws NullPointerException if definition, source is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public synchronized Activation activate(WorkflowDefinition definition, WorkflowDefinitionSourceMetadata source) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(source, "source");
        WorkflowDefinition existing = definitions.get(definition.key());
        if (existing != null && !existing.revision().equals(definition.revision())) {
            throw new IllegalArgumentException("Workflow definition " + definition.key()
                    + " is immutable; changed content requires a new version");
        }
        boolean changed = existing == null;
        WorkflowDefinition effective = existing == null ? definition : existing;
        if (changed) definitions.put(effective.key(), effective);
        ActivatedWorkflowDefinition activated = new ActivatedWorkflowDefinition(effective, source);
        activeSources.put(source.location(), activated);
        return new Activation(activated, changed);
    }

    /**
     * Looks up the last successfully activated definition for a source location.
     * @param location source location used for provenance and diagnostics
     * @return the matching value, or an empty optional when absent
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public Optional<ActivatedWorkflowDefinition> activeSource(String location) {
        if (location == null || location.isBlank()) throw new IllegalArgumentException("location is required");
        return Optional.ofNullable(activeSources.get(location));
    }

    /**
     * Returns a snapshot of successfully activated source locations and definitions.
     * @return a snapshot of successfully activated source locations and definitions
     */
    public Map<String, ActivatedWorkflowDefinition> activeSources() { return Map.copyOf(activeSources); }

    /**
     * Registry activation outcome retaining the selected definition and whether it changed.
     * @param active active definition retained after the attempt, when available
     * @param changed whether registry activation selected a different definition
     */
    public record Activation(ActivatedWorkflowDefinition active, boolean changed) {
        /**
         * Creates this value from the supplied components.
         * @param active active definition retained after the attempt, when available
         * @param changed whether registry activation selected a different definition
         * @throws NullPointerException if active is null
         */
        public Activation { Objects.requireNonNull(active, "active"); }
    }

    private static String key(String workflowName, String workflowVersion) {
        if (workflowName == null || workflowName.isBlank()) {
            throw new IllegalArgumentException("workflowName is required");
        }
        if (workflowVersion == null || workflowVersion.isBlank()) {
            throw new IllegalArgumentException("workflowVersion is required");
        }
        return workflowName + ":" + workflowVersion;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            String leftPart = index < leftParts.length ? leftParts[index] : "0";
            String rightPart = index < rightParts.length ? rightParts[index] : "0";
            int comparison;
            try {
                comparison = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
            } catch (NumberFormatException ignored) {
                comparison = leftPart.compareTo(rightPart);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
