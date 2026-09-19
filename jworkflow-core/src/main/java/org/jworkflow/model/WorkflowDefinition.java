package org.jworkflow.model;


import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Immutable workflow graph identified by name, version and a semantic checksum revision. Node and metadata maps
 *  are defensively copied; the start node must exist. Optional original DSL text is retained for provenance but is
 *  not included in the semantic checksum. Durable instances must retain their exact revision rather than silently
 *  follow a replacement definition.
 * @param name workflow definition name
 * @param version declared workflow or format version
 * @param startNode name of the graph's initial node
 * @param nodes workflow nodes indexed by name, or the nodes used to build that index
 * @param metadata definition metadata included in the semantic checksum
 * @param sourceText optional original DSL text; not an executable closure
 */
public record WorkflowDefinition(
        String name,
        String version,
        String startNode,
        Map<String, WorkflowNode> nodes,
        Map<String, String> metadata,
        String sourceText
) {
    /**
     * Creates this value from the supplied components.
     * @param name workflow definition name
     * @param version declared workflow or format version
     * @param startNode name of the graph's initial node
     * @param nodes workflow nodes indexed by name, or the nodes used to build that index
     * @param metadata definition metadata included in the semantic checksum
     */
    public WorkflowDefinition(String name, String version, String startNode,
                              Map<String, WorkflowNode> nodes, Map<String, String> metadata) {
        this(name, version, startNode, nodes, metadata, null);
    }
    /**
     * Creates this value from the supplied components.
     * @param name workflow definition name
     * @param version declared workflow or format version
     * @param startNode name of the graph's initial node
     * @param nodes workflow nodes indexed by name, or the nodes used to build that index
     * @param metadata definition metadata included in the semantic checksum
     * @param sourceText optional original DSL text; not an executable closure
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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

    /**
     * Creates a definition from named nodes, rejecting duplicate node names. Metadata is empty and original source
     *  text is absent.
     * @param name workflow definition name
     * @param version declared workflow or format version
     * @param startNode name of the graph's initial node
     * @param nodes workflow nodes indexed by name, or the nodes used to build that index
     * @return the immutable workflow definition
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public static WorkflowDefinition of(String name, String version, String startNode, WorkflowNode... nodes) {
        Map<String, WorkflowNode> byName = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (byName.putIfAbsent(node.name(), node) != null) {
                throw new IllegalArgumentException("Duplicate workflow node: " + node.name());
            }
        }
        return new WorkflowDefinition(name, version, startNode, byName, Map.of(), null);
    }

    /**
     * Returns the name:version registry identity; it is not the semantic revision.
     * @return the name:version registry identity; it is not the semantic revision
     */
    public String key() {
        return name + ":" + version;
    }

    /**
     * Returns the semantic checksum used to pin durable instances to this exact graph.
     * @return the semantic checksum used to pin durable instances to this exact graph
     */
    public String revision() {
        return checksum();
    }

    /**
     * Computes the SHA-256 semantic revision from ordered graph/metadata content, excluding original source text.
     * @return the resulting text
     */
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
