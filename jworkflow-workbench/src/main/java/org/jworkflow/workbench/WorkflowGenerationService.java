package org.jworkflow.workbench;

import java.math.BigDecimal;
import java.util.*;
import org.jworkflow.definition.WorkflowDefinitionText;
import org.jworkflow.dsl.GroovyWorkflowDslCompiler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure, bounded validation and the single canonical M3 Groovy emitter. */
public final class WorkflowGenerationService {
    public static final int MAX_BLOCKS = 500;
    public static final int MAX_DEPTH = 10;
    public static final String RULESET_VERSION = "m3-rules-1";
    private static final Set<String> TYPES = Set.of("workflow_start", "workflow_step", "workflow_branch", "workflow_end");
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains");
    private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits", "non-sealed", "_");
    /** Blockly nests each chained block about two JSON levels deep; 500 blocks exceed Jackson's default nesting limit. */
    static final int MAX_JSON_DEPTH = 2_500;
    private final JsonMapper json = workflowJson();

    /** Bounded mapper for workflow documents: deep enough for the 500-block scale target, still a hard limit. */
    static JsonMapper workflowJson() {
        var factory = tools.jackson.core.json.JsonFactory.builder()
                .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH).build())
                .streamWriteConstraints(tools.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH).build()).build();
        return JsonMapper.builder(factory).build();
    }

    /** Refuses a rewrite when a future editor's block would be lost by this fixed MVP palette. */
    public static boolean hasUnsupportedBlock(String blockly) {
        try {
            JsonNode root = workflowJson().readTree(blockly == null || blockly.isBlank() ? "{}" : blockly);
            JsonNode blocks = root.path("blocks");
            if (blocks.isMissingNode() || blocks.isNull()) return false;
            if (!blocks.isObject()) return true;
            JsonNode top = blocks.path("blocks");
            return (!top.isMissingNode() && !top.isArray()) || hasUnsupported(top);
        } catch (RuntimeException malformed) { return true; }
    }

    private static boolean hasUnsupported(JsonNode values) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (hasUnsupportedBlockNode(value)) return true;
        return false;
    }

    private static boolean hasUnsupportedBlockNode(JsonNode block) {
        if (!TYPES.contains(block.path("type").asText())) return true;
        JsonNode inputs = block.path("inputs");
        if (inputs.isObject()) for (var input : inputs.properties()) if (hasUnsupportedBlockNodeOrMissing(input.getValue().path("block"))) return true;
        return hasUnsupportedBlockNodeOrMissing(block.path("next").path("block"));
    }

    private static boolean hasUnsupportedBlockNodeOrMissing(JsonNode block) {
        return !block.isMissingNode() && !block.isNull() && hasUnsupportedBlockNode(block);
    }

    public GenerationResult generate(ProjectWorkspace.DraftView draft, List<ProjectWorkspace.Binding> bindings,
            boolean replacement, String replacementHash) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Parsed parsed = parse(draft.blockly(), diagnostics);
        validateMetadata(draft, diagnostics);
        validateGraph(parsed, bindings, draft.javaPackage(), diagnostics);
        List<SharedRuleResult> sharedRules = sharedRules(draft, parsed);
        String groovy = "";
        List<SourceRange> sourceMap = List.of();
        if (!hasErrors(diagnostics)) {
            Emission emission = emit(draft, parsed);
            groovy = emission.source();
            sourceMap = emission.sourceMap();
            try {
                new GroovyWorkflowDslCompiler().compile(new WorkflowDefinitionText(draft.engineId() + ".groovy", groovy));
            } catch (RuntimeException | LinkageError rejected) {
                diagnostics.add(error("CORE_REJECTED", "Pinned core rejected the generated definition: " + safe(rejected), "", "", "Correct the highlighted workflow structure."));
                groovy = "";
                sourceMap = List.of();
            }
        }
        return new GenerationResult(draft.documentId(), draft.revision(), draft.hash(), RULESET_VERSION,
                List.copyOf(diagnostics), sharedRules, groovy, sourceMap,
                new Compatibility("0.1.0-SNAPSHOT", "Groovy restricted DSL", "Java 17+"), replacement, replacementHash,
                groovy.isEmpty() ? "blocked" : "ready");
    }

    private Parsed parse(String blockly, List<Diagnostic> diagnostics) {
        try {
            JsonNode root = json.readTree(blockly == null || blockly.isBlank() ? "{}" : blockly);
            JsonNode tops = root.path("blocks").path("blocks");
            if (!tops.isArray()) return new Parsed(List.of(), List.of(), 0);
            ArrayList<Block> all = new ArrayList<>();
            ArrayList<Block> top = new ArrayList<>();
            int[] maxDepth = {0};
            for (JsonNode node : tops) top.add(read(node, 1, all, diagnostics, maxDepth));
            if (all.size() > MAX_BLOCKS) diagnostics.add(error("LIMIT_BLOCKS", "Workflow exceeds " + MAX_BLOCKS + " blocks.", "", "", "Delete blocks before generating."));
            return new Parsed(List.copyOf(top), List.copyOf(all), maxDepth[0]);
        } catch (RuntimeException invalid) {
            diagnostics.add(error("DOCUMENT_INVALID", "Blockly state is not valid JSON.", "", "", "Reload the saved draft or repair the document."));
            return new Parsed(List.of(), List.of(), 0);
        }
    }

    private Block read(JsonNode node, int depth, List<Block> all, List<Diagnostic> diagnostics, int[] maxDepth) {
        String id = text(node, "id"), type = text(node, "type");
        maxDepth[0] = Math.max(maxDepth[0], depth);
        if (depth > MAX_DEPTH) diagnostics.add(error("LIMIT_DEPTH", "Block nesting exceeds " + MAX_DEPTH + " levels.", id, "", "Flatten the workflow."));
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        JsonNode fieldNode = node.path("fields");
        if (fieldNode.isObject()) fieldNode.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().path("value").asText()));
        boolean disabled = node.path("disabled").asBoolean(false)
                || (node.has("enabled") && !node.path("enabled").asBoolean(true))
                || (node.path("disabledReasons").isArray() && !node.path("disabledReasons").isEmpty());
        Block block = new Block(id, type, Map.copyOf(fields), disabled, new ArrayList<>());
        all.add(block);
        JsonNode inputs = node.path("inputs");
        if (inputs.isObject()) inputs.properties().forEach(entry -> {
            JsonNode child = entry.getValue().path("block");
            if (!child.isMissingNode()) readChain(child, depth + 1, block.children(), all, diagnostics, maxDepth);
        });
        return block;
    }

    private void readChain(JsonNode node, int depth, List<Block> destination, List<Block> all, List<Diagnostic> diagnostics, int[] maxDepth) {
        JsonNode current = node;
        int guard = 0;
        while (!current.isMissingNode() && !current.isNull() && guard++ <= MAX_BLOCKS) {
            destination.add(read(current, depth, all, diagnostics, maxDepth));
            current = current.path("next").path("block");
        }
    }

    private static List<SharedRuleResult> sharedRules(ProjectWorkspace.DraftView draft, Parsed parsed) {
        return List.of(
                shared("required", "name", SharedValidationRules.REQUIRED, draft.name().length()),
                shared("required", "engineId", SharedValidationRules.REQUIRED, draft.engineId().length()),
                shared("required", "version", SharedValidationRules.REQUIRED, draft.version().length()),
                shared("maximum", "blocks", SharedValidationRules.MAX_BLOCKS, parsed.all().size()),
                shared("maximum", "depth", SharedValidationRules.MAX_DEPTH, parsed.maxDepth()));
    }
    private static SharedRuleResult shared(String rule, String location, int ruleId, int value) {
        return new SharedRuleResult(rule, location, value, SharedValidationRules.check(ruleId, value));
    }

    private void validateMetadata(ProjectWorkspace.DraftView draft, List<Diagnostic> out) {
        if (!draft.javaPackage().isBlank() && !isJavaPackage(draft.javaPackage()))
            out.add(error("JAVA_PACKAGE_INVALID", "Java package is not a valid package name: " + draft.javaPackage(), "", "javaPackage", "Enter a dotted package name such as com.example.orders."));
        required(draft.name(), "META_NAME_REQUIRED", "Workflow name is required.", "name", out);
        required(draft.engineId(), "META_ENGINE_ID_REQUIRED", "Engine ID is required.", "engineId", out);
        required(draft.version(), "META_VERSION_REQUIRED", "Workflow version is required.", "version", out);
        if (!draft.engineId().isBlank() && !draft.engineId().matches("[A-Za-z][A-Za-z0-9._-]{0,127}"))
            out.add(error("META_ENGINE_ID_INVALID", "Engine ID must start with a letter and contain only letters, digits, dot, underscore or hyphen.", "", "engineId", "Rename the workflow or correct its engine ID."));
    }

    private void validateGraph(Parsed parsed, List<ProjectWorkspace.Binding> bindings, String javaPackage, List<Diagnostic> out) {
        List<Block> starts = parsed.top().stream().filter(b -> b.type().equals("workflow_start")).toList();
        if (starts.isEmpty()) out.add(error("START_REQUIRED", "Add one workflow start block.", "", "", "Insert a Start block and attach workflow nodes."));
        if (starts.size() > 1) starts.subList(1, starts.size()).forEach(b -> out.add(error("START_MULTIPLE", "Only one workflow start block is supported.", b.id(), "", "Delete the extra Start block.")));
        Set<Block> attached = Collections.newSetFromMap(new IdentityHashMap<>());
        starts.forEach(start -> { attached.add(start); collect(start.children(), attached); });
        for (Block block : parsed.all()) {
            if (!TYPES.contains(block.type())) out.add(error("BLOCK_UNSUPPORTED", "Unsupported block type: " + block.type(), block.id(), "", "Delete the block or use a supported palette item."));
            if (!attached.contains(block)) out.add(block.disabled()
                    ? warning("BLOCK_ORPHAN_DISABLED", "Disabled detached block is ignored.", block.id(), "", "Attach or delete it before enabling it.")
                    : error("BLOCK_ORPHAN", "Enabled detached block cannot be generated.", block.id(), "", "Attach it beneath the Start block or disable it."));
        }
        if (starts.isEmpty()) return;
        Block start = starts.get(0);
        required(field(start, "START"), "START_TARGET_REQUIRED", "Start target is required.", start.id(), "START", out);
        List<Block> nodes = start.children().stream().filter(b -> !b.disabled()).toList();
        LinkedHashMap<String, Block> names = new LinkedHashMap<>();
        Set<String> actions = new LinkedHashSet<>();
        bindings.stream().filter(b -> b.role().equals("command")).forEach(b -> { actions.add(b.name()); actions.add(b.packageName().isBlank() ? b.name() : b.packageName() + "." + b.name()); });
        for (Block node : nodes) {
            String name = field(node, "NAME");
            required(name, "NODE_NAME_REQUIRED", "Node name is required.", node.id(), "NAME", out);
            if (!name.isBlank() && names.putIfAbsent(name, node) != null) out.add(error("NODE_NAME_DUPLICATE", "Node name is duplicated: " + name, node.id(), "NAME", "Use a unique node name."));
            if (node.type().equals("workflow_step")) {
                String action = field(node, "ACTION");
                required(action, "ACTION_REQUIRED", "A command action is required.", node.id(), "ACTION", out);
                if (!action.isBlank() && !actions.contains(action)) {
                    // INT-01: a missing command is proposed for creation as a record in the confirmed package.
                    if (!isJavaIdentifier(action)) out.add(error("ACTION_UNKNOWN", "Command action was not discovered and is not a valid new Java class name: " + action, node.id(), "ACTION", "Choose a discovered command or enter a simple Java class name to create one."));
                    else if (javaPackage == null || javaPackage.isBlank()) out.add(error("JAVA_PACKAGE_REQUIRED", "Command " + action + " was not discovered; a Java package is needed to create it.", node.id(), "ACTION", "Confirm a Java package or choose a discovered command."));
                    else out.add(warning("COMMAND_WILL_BE_CREATED", "Command " + action + " was not discovered; the source proposal will create record " + javaPackage + "." + action + ".", node.id(), "ACTION", "Review the new command in the source proposal, or choose a discovered command."));
                }
                required(field(node, "SUCCESS"), "SUCCESS_REQUIRED", "Success target is required.", node.id(), "SUCCESS", out);
                required(field(node, "FAILURE"), "FAILURE_REQUIRED", "Failure target is required.", node.id(), "FAILURE", out);
                identical(node, "SUCCESS", "FAILURE", out);
            } else if (node.type().equals("workflow_branch")) {
                required(field(node, "VARIABLE"), "VARIABLE_REQUIRED", "Branch variable is required.", node.id(), "VARIABLE", out);
                if (!OPERATORS.contains(field(node, "OPERATOR"))) out.add(error("OPERATOR_UNSUPPORTED", "Choose a supported comparison operator.", node.id(), "OPERATOR", "Choose eq, ne, gt, gte, lt, lte or contains."));
                validateLiteral(node, out);
                required(field(node, "TRUE_TARGET"), "TRUE_TARGET_REQUIRED", "Matching target is required.", node.id(), "TRUE_TARGET", out);
                required(field(node, "FALSE_TARGET"), "FALSE_TARGET_REQUIRED", "Fallback target is required.", node.id(), "FALSE_TARGET", out);
                identical(node, "TRUE_TARGET", "FALSE_TARGET", out);
            }
        }
        Set<String> known = names.keySet();
        reference(field(start, "START"), known, start, "START", out);
        for (Block node : nodes) {
            if (node.type().equals("workflow_step")) { reference(field(node, "SUCCESS"), known, node, "SUCCESS", out); reference(field(node, "FAILURE"), known, node, "FAILURE", out); }
            if (node.type().equals("workflow_branch")) { reference(field(node, "TRUE_TARGET"), known, node, "TRUE_TARGET", out); reference(field(node, "FALSE_TARGET"), known, node, "FALSE_TARGET", out); }
        }
    }

    private static void identical(Block node, String first, String second, List<Diagnostic> out) {
        String a = field(node, first), b = field(node, second);
        if (!a.isBlank() && a.equals(b)) out.add(error("TARGETS_IDENTICAL", "Both routes of '" + field(node, "NAME") + "' go to '" + a + "'; the pinned core needs different targets.",
                node.id(), second, "Route the second outcome to a different node, for example a separate end."));
    }

    private void validateLiteral(Block node, List<Diagnostic> out) {
        String type = field(node, "VALUE_TYPE"), value = field(node, "VALUE");
        if (!Set.of("STRING", "NUMBER", "BOOLEAN", "NULL").contains(type)) {
            out.add(error("LITERAL_TYPE_UNSUPPORTED", "Choose a supported literal type.", node.id(), "VALUE_TYPE", "Choose string, number, boolean or null.")); return;
        }
        if (type.equals("NULL")) return;
        required(value, "LITERAL_REQUIRED", "Comparison value is required.", node.id(), "VALUE", out);
        if (type.equals("NUMBER") && !value.isBlank()) try { new BigDecimal(value); } catch (NumberFormatException invalid) { out.add(error("LITERAL_NUMBER_INVALID", "Number is not a finite decimal.", node.id(), "VALUE", "Enter a decimal number.")); }
        if (type.equals("BOOLEAN") && !(value.equals("true") || value.equals("false"))) out.add(error("LITERAL_BOOLEAN_INVALID", "Boolean must be true or false.", node.id(), "VALUE", "Enter true or false."));
    }

    private Emission emit(ProjectWorkspace.DraftView draft, Parsed parsed) {
        Block start = parsed.top().stream().filter(b -> b.type().equals("workflow_start")).findFirst().orElseThrow();
        StringBuilder source = new StringBuilder(); List<SourceRange> map = new ArrayList<>(); int line = 1;
        source.append("workflow(").append(quote(draft.engineId())).append(") {\n"); line++;
        source.append("  version ").append(quote(draft.version())).append("\n"); line++;
        source.append("  start at: ").append(quote(field(start, "START"))).append("\n");
        map.add(new SourceRange(start.id(), line, line)); line++;
        for (Block node : start.children()) {
            if (node.disabled()) continue;
            int first = line;
            if (node.type().equals("workflow_step")) {
                source.append("  step(").append(quote(field(node, "NAME"))).append(") {\n");
                source.append("    action ").append(quote(field(node, "ACTION"))).append("\n");
                source.append("    onSuccess goTo: ").append(quote(field(node, "SUCCESS"))).append("\n");
                source.append("    onFailure goTo: ").append(quote(field(node, "FAILURE"))).append("\n");
                source.append("  }\n"); line += 5;
            } else if (node.type().equals("workflow_branch")) {
                source.append("  gateway(").append(quote(field(node, "NAME"))).append(", type: 'exclusive') {\n");
                source.append("    when variable: ").append(quote(field(node, "VARIABLE"))).append(", ").append(field(node, "OPERATOR")).append(": ").append(literal(node)).append(", goTo: ").append(quote(field(node, "TRUE_TARGET"))).append("\n");
                source.append("    otherwise goTo: ").append(quote(field(node, "FALSE_TARGET"))).append("\n");
                source.append("  }\n"); line += 4;
            } else if (node.type().equals("workflow_end")) {
                source.append("  end(").append(quote(field(node, "NAME"))).append(")\n"); line++;
            }
            map.add(new SourceRange(node.id(), first, line - 1));
        }
        source.append("}\n");
        return new Emission(source.toString(), List.copyOf(map));
    }

    /** Enabled step actions attached beneath the Start block, in workflow order and without duplicates. */
    static List<String> stepActions(String blockly) {
        Parsed parsed = new WorkflowGenerationService().parse(blockly, new ArrayList<>());
        return parsed.top().stream().filter(b -> b.type().equals("workflow_start")).findFirst()
                .map(start -> start.children().stream().filter(b -> !b.disabled() && b.type().equals("workflow_step"))
                        .map(b -> field(b, "ACTION")).filter(a -> !a.isBlank()).distinct().toList())
                .orElse(List.of());
    }
    static boolean isJavaIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !JAVA_KEYWORDS.contains(value);
    }
    static boolean isJavaPackage(String value) {
        return value != null && !value.isBlank() && Arrays.stream(value.split("\\.", -1)).allMatch(WorkflowGenerationService::isJavaIdentifier);
    }
    private static String literal(Block block) {
        String type = field(block, "VALUE_TYPE"), value = field(block, "VALUE");
        return switch (type) { case "NUMBER" -> new BigDecimal(value).toPlainString(); case "BOOLEAN" -> value; case "NULL" -> "null"; default -> quote(value); };
    }
    static String quote(String value) {
        StringBuilder out = new StringBuilder("'");
        for (char c : value.toCharArray()) switch (c) {
            case '\\' -> out.append("\\\\"); case '\'' -> out.append("\\'"); case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t"); default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }
        return out.append('\'').toString();
    }
    private static String field(Block block, String name) { return block.fields().getOrDefault(name, "").strip(); }
    private static String text(JsonNode node, String name) { return node.path(name).asText(""); }
    private static void collect(List<Block> values, Set<Block> into) { for (Block value : values) { into.add(value); collect(value.children(), into); } }
    private static void required(String value, String code, String message, String field, List<Diagnostic> out) { required(value, code, message, "", field, out); }
    private static void required(String value, String code, String message, String block, String field, List<Diagnostic> out) { if (value == null || value.isBlank()) out.add(error(code, message, block, field, "Enter a value.")); }
    private static void reference(String value, Set<String> known, Block block, String field, List<Diagnostic> out) { if (!value.isBlank() && !known.contains(value)) out.add(error("REFERENCE_UNKNOWN", "Target does not name an enabled node: " + value, block.id(), field, "Choose an enabled node name.")); }
    private static boolean hasErrors(List<Diagnostic> values) { return values.stream().anyMatch(d -> d.severity().equals("error")); }
    private static Diagnostic error(String code, String message, String block, String field, String correction) { return new Diagnostic(code, "error", message, correction, block, field); }
    private static Diagnostic warning(String code, String message, String block, String field, String correction) { return new Diagnostic(code, "warning", message, correction, block, field); }
    private static String safe(Throwable failure) { String value = failure.getMessage(); if (value == null || value.isBlank()) return failure.getClass().getSimpleName(); int newline = value.indexOf('\n'); return newline < 0 ? value : value.substring(0, newline); }

    private record Block(String id, String type, Map<String, String> fields, boolean disabled, List<Block> children) {}
    private record Parsed(List<Block> top, List<Block> all, int maxDepth) {}
    private record Emission(String source, List<SourceRange> sourceMap) {}
    public record Diagnostic(String code, String severity, String message, String correction, String blockId, String field) {}
    public record SourceRange(String blockId, int startLine, int endLine) {}
    public record Compatibility(String coreVersion, String format, String javaTarget) {}
    public record SharedRuleResult(String rule, String location, int value, int result) {}
    public record GenerationResult(String documentId, long revision, String documentHash, String rulesetVersion,
            List<Diagnostic> diagnostics, List<SharedRuleResult> sharedRules, String groovy, List<SourceRange> sourceMap, Compatibility compatibility,
            boolean replacement, String replacementHash, String state) {}
}
