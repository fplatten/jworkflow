package org.jworkflow.workbench;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only assistant tool: a bounded, structured proposal of workflow block edits (AI-04, AI-06). Arguments are
 * validated completely before anything is shown; nothing is executed and the editor applies an approved proposal
 * itself. Proposals cannot invent actions: a step action must be a discovered command.
 */
final class WorkflowEditProposals {
    static final String TOOL = "propose_workflow_edit";
    static final int MAX_OPERATIONS = 20;
    private static final int MAX_VALUE = 200;
    private static final Map<String, List<String>> FIELDS = Map.of(
            "step", List.of("NAME", "ACTION", "SUCCESS", "FAILURE"),
            "branch", List.of("NAME", "VARIABLE", "OPERATOR", "VALUE_TYPE", "VALUE", "TRUE_TARGET", "FALSE_TARGET"),
            "end", List.of("NAME"));
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains");
    private static final Set<String> VALUE_TYPES = Set.of("STRING", "NUMBER", "BOOLEAN", "NULL");

    record Operation(String op, String type, String node, String after, String field, String value, Map<String, String> fields) {}
    record Proposal(String id, String contextFingerprint, String summary, List<Operation> operations, List<String> preview) {}

    private WorkflowEditProposals() {}

    static String schema() {
        return """
                {"type":"object","additionalProperties":false,"required":["summary","operations"],"properties":{
                 "summary":{"type":"string","description":"One or two sentences describing the change for the user."},
                 "operations":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["op"],"properties":{
                  "op":{"enum":["add_node","set_field","remove_node","set_start"]},
                  "type":{"enum":["step","branch","end"],"description":"add_node only."},
                  "after":{"type":"string","description":"add_node only: existing node name to insert after; omit to append to the end of the start block."},
                  "fields":{"type":"object","additionalProperties":{"type":"string"},"description":"add_node only: block fields. step: NAME, ACTION (a discovered command), SUCCESS, FAILURE. branch: NAME, VARIABLE, OPERATOR (eq|ne|gt|gte|lt|lte|contains), VALUE_TYPE (STRING|NUMBER|BOOLEAN|NULL), VALUE, TRUE_TARGET, FALSE_TARGET. end: NAME."},
                  "node":{"type":"string","description":"set_field/remove_node: existing node name."},
                  "field":{"type":"string","description":"set_field: field name valid for that node's block type."},
                  "value":{"type":"string","description":"set_field: new value; set_start: target node name."}}}}}}
                """;
    }

    static String description() {
        return "Propose edits to the user's JWorkflow workflow blocks. The user reviews a preview and approves or rejects the whole proposal; "
                + "nothing changes without approval. Use only discovered command names as step actions. Do not propose source code, build files or service wiring.";
    }

    /**
     * @param nodeTypes current node name to block type (empty when the workflow was not shared)
     * @throws AssistantFailure MALFORMED with a reason when any part is invalid; nothing is partially accepted
     */
    static Proposal validate(String arguments, Set<String> commands, Map<String, String> nodeTypes, String fingerprint) {
        JsonNode root;
        try { root = JsonMapper.builder().build().readTree(arguments == null ? "" : arguments); }
        catch (RuntimeException invalid) { throw reject("its arguments are not valid JSON"); }
        if (root == null || !root.isObject()) throw reject("its arguments are not an object");
        allowOnly(root, Set.of("summary", "operations"));
        String summary = text(root, "summary", 500, true);
        JsonNode ops = root.path("operations");
        if (!ops.isArray() || ops.isEmpty() || ops.size() > MAX_OPERATIONS) throw reject("it must contain 1 to " + MAX_OPERATIONS + " operations");
        Map<String, String> names = new LinkedHashMap<>(nodeTypes);
        List<Operation> operations = new ArrayList<>();
        List<String> preview = new ArrayList<>();
        for (JsonNode op : ops) {
            if (!op.isObject()) throw reject("an operation is not an object");
            allowOnly(op, Set.of("op", "type", "after", "fields", "node", "field", "value"));
            switch (op.path("op").asText()) {
                case "add_node" -> {
                    String type = op.path("type").asText();
                    if (!FIELDS.containsKey(type)) throw reject("add_node needs type step, branch or end");
                    String after = text(op, "after", MAX_VALUE, false);
                    if (!after.isEmpty() && !names.containsKey(after)) throw reject("add_node refers to unknown node '" + after + "'");
                    JsonNode given = op.path("fields");
                    if (!given.isObject()) throw reject("add_node needs a fields object");
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (var entry : given.properties()) {
                        if (!FIELDS.get(type).contains(entry.getKey())) throw reject("field " + entry.getKey() + " is not valid for a " + type);
                        if (!entry.getValue().isString()) throw reject("field " + entry.getKey() + " must be a string");
                        fields.put(entry.getKey(), value(type, entry.getKey(), entry.getValue().asText(), commands));
                    }
                    String name = fields.getOrDefault("NAME", "");
                    if (name.isBlank()) throw reject("add_node needs a NAME");
                    if (names.containsKey(name)) throw reject("node name '" + name + "' already exists");
                    names.put(name, type);
                    operations.add(new Operation("add_node", type, "", after, "", "", Map.copyOf(fields)));
                    preview.add("Add " + type + " '" + name + "'" + (after.isEmpty() ? " at the end of the workflow" : " after '" + after + "'") + describe(fields));
                }
                case "set_field" -> {
                    String node = text(op, "node", MAX_VALUE, true), field = text(op, "field", 32, true);
                    String type = names.get(node);
                    if (type == null) throw reject("set_field refers to unknown node '" + node + "'");
                    if (!FIELDS.get(type).contains(field)) throw reject("field " + field + " is not valid for a " + type);
                    String value = value(type, field, text(op, "value", MAX_VALUE, false), commands);
                    if (field.equals("NAME")) { if (value.isBlank() || names.containsKey(value)) throw reject("rename to '" + value + "' is empty or already used"); names.remove(node); names.put(value, type); }
                    operations.add(new Operation("set_field", "", node, "", field, value, Map.of()));
                    preview.add("Set " + field + " of '" + node + "' to '" + value + "'");
                }
                case "remove_node" -> {
                    String node = text(op, "node", MAX_VALUE, true);
                    if (names.remove(node) == null) throw reject("remove_node refers to unknown node '" + node + "'");
                    operations.add(new Operation("remove_node", "", node, "", "", "", Map.of()));
                    preview.add("Remove '" + node + "'");
                }
                case "set_start" -> {
                    String target = text(op, "value", MAX_VALUE, true);
                    if (!names.containsKey(target)) throw reject("set_start refers to unknown node '" + target + "'");
                    operations.add(new Operation("set_start", "", "", "", "START", target, Map.of()));
                    preview.add("Start the workflow at '" + target + "'");
                }
                default -> throw reject("operation '" + op.path("op").asText() + "' is not supported");
            }
        }
        return new Proposal(UUID.randomUUID().toString(), fingerprint, summary, List.copyOf(operations), List.copyOf(preview));
    }

    private static String value(String type, String field, String value, Set<String> commands) {
        if (field.equals("ACTION") && !commands.contains(value)) throw reject("step action '" + value + "' is not a discovered command; the assistant cannot invent actions");
        if (field.equals("OPERATOR") && !OPERATORS.contains(value)) throw reject("operator '" + value + "' is not supported");
        if (field.equals("VALUE_TYPE") && !VALUE_TYPES.contains(value)) throw reject("value type '" + value + "' is not supported");
        return value;
    }

    private static String describe(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        fields.forEach((key, value) -> { if (!key.equals("NAME")) parts.add(key.toLowerCase(Locale.ROOT) + " " + value); });
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static String text(JsonNode node, String name, int max, boolean required) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) { if (required) throw reject(name + " is required"); return ""; }
        if (!value.isString()) throw reject(name + " must be a string");
        String text = value.asText();
        if (required && text.isBlank()) throw reject(name + " is required");
        if (text.length() > max) throw reject(name + " exceeds " + max + " characters");
        if (text.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) throw reject(name + " contains control characters");
        return text.strip();
    }

    private static void allowOnly(JsonNode node, Set<String> allowed) {
        for (String key : node.propertyNames()) if (!allowed.contains(key)) throw reject("it contains unsupported property '" + key + "'");
    }

    private static AssistantFailure reject(String reason) {
        return new AssistantFailure(AssistantFailure.Kind.MALFORMED, "The assistant's proposal was rejected because " + reason + ". Nothing was changed.");
    }
}
