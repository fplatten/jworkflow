package org.jworkflow.model;


import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record WorkflowDefinition(
        String name,
        String version,
        String startNode,
        Map<String, WorkflowNode> nodes,
        Map<String, String> metadata,
        String sourceText
) {
    public WorkflowDefinition(String name, String version, String startNode,
                              Map<String, WorkflowNode> nodes, Map<String, String> metadata) {
        this(name, version, startNode, nodes, metadata, null);
    }
    public WorkflowDefinition {
        requireText(name, "name");
        requireText(version, "version");
        requireText(startNode, "startNode");
        nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (!nodes.containsKey(startNode)) {
            throw new IllegalArgumentException("startNode must reference a known node: " + startNode);
        }
    }

    public static WorkflowDefinition of(String name, String version, String startNode, WorkflowNode... nodes) {
        Map<String, WorkflowNode> byName = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (byName.putIfAbsent(node.name(), node) != null) {
                throw new IllegalArgumentException("Duplicate workflow node: " + node.name());
            }
        }
        return new WorkflowDefinition(name, version, startNode, byName, Map.of(), null);
    }

    public String key() {
        return name + ":" + version;
    }

    public String revision() {
        return checksum();
    }

    public String checksum() {
        StringBuilder canonical = new StringBuilder(name).append('|').append(version).append('|').append(startNode);
        nodes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
