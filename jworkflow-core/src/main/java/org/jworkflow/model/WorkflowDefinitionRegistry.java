package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class WorkflowDefinitionRegistry {
    private final ConcurrentMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActivatedWorkflowDefinition> activeSources = new ConcurrentHashMap<>();

    public void register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        WorkflowDefinition prior = definitions.putIfAbsent(definition.key(), definition);
        if (prior != null) {
            throw new IllegalArgumentException("Workflow definition already registered: " + definition.key());
        }
    }

    public Optional<WorkflowDefinition> find(String workflowName, String workflowVersion) {
        return Optional.ofNullable(definitions.get(key(workflowName, workflowVersion)));
    }

    public Optional<WorkflowDefinition> latest(String workflowName) {
        if (workflowName == null || workflowName.isBlank()) {
            throw new IllegalArgumentException("workflowName is required");
        }
        return definitions.values().stream()
                .filter(definition -> definition.name().equals(workflowName))
                .sorted((left, right) -> compareVersions(right.version(), left.version()))
                .findFirst();
    }

    public WorkflowDefinition require(String workflowName, String workflowVersion) {
        return find(workflowName, workflowVersion)
                .orElseThrow(() -> new WorkflowDefinitionNotFoundException(workflowName, workflowVersion));
    }

    public Map<String, WorkflowDefinition> snapshot() {
        return Map.copyOf(definitions);
    }

    /**
     * Activates one fully compiled and validated candidate. Definition identities are immutable: changed
     * content must use a new version. Prior versions remain registered for pinned workflow instances.
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

    public Optional<ActivatedWorkflowDefinition> activeSource(String location) {
        if (location == null || location.isBlank()) throw new IllegalArgumentException("location is required");
        return Optional.ofNullable(activeSources.get(location));
    }

    public Map<String, ActivatedWorkflowDefinition> activeSources() { return Map.copyOf(activeSources); }

    public record Activation(ActivatedWorkflowDefinition active, boolean changed) {
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
