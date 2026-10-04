package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Owns the one confirmed project root and all M2 file access below it. */
public final class ProjectWorkspace {
    private static final long MAX_SOURCE_BYTES = 1_048_576;
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?m)\\b(record|class|interface)\\s+(\\w+)(?:\\s*\\([^)]*\\))?(?:\\s+extends\\s+[\\w.<>?, ]+)?(?:\\s+implements\\s+([^\\{]+))?");
    private static final Pattern RECORD_FIELDS = Pattern.compile("(?m)\\brecord\\s+\\w+\\s*\\(([^)]*)\\)");
    private final LastProjectPreference preference;
    private Path root;
    private ProjectProfile profile;
    private String discoveryFingerprint = "";
    private ChangeSetStore store;
    private String recovery = "";
    private static final String APPLIED = ".jworkflow/applied.json";
    private static final String VERIFICATION = ".jworkflow/verification.json";
    private static final Set<String> NOT_SOURCES = Set.of(".jworkflow", ".git", "target", "build", "out", ".gradle", "node_modules", ".idea", ".vscode");
    private java.util.function.BooleanSupplier sourceWritesBlocked = () -> false;

    public ProjectWorkspace(LastProjectPreference preference) { this.preference = preference; }

    public synchronized WorkspaceState confirm(String requestedRoot) throws IOException {
        if (requestedRoot == null || requestedRoot.isBlank()) throw new IllegalArgumentException("Choose a project root.");
        Path candidate = Path.of(requestedRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate))
            throw new IllegalArgumentException("Project root must be an existing directory, not a link.");
        // Resolve linked ancestors (macOS /var -> /private/var, Windows junctions) so later checks compare real paths.
        Path real = candidate.toRealPath();
        ProjectProfile inspected = inspect(real);
        root = real; profile = inspected; discoveryFingerprint = fingerprintSources();
        store = new ChangeSetStore(this::resolve);
        boolean interrupted = Files.exists(resolve(ChangeSetStore.JOURNAL + "/journal.json"), LinkOption.NOFOLLOW_LINKS);
        String pending = store.recover();
        recovery = pending != null ? "An interrupted source change could not be recovered: " + pending
                : interrupted ? "An interrupted source change was found and completed or rolled back; no partial change remains." : "";
        preference.remember(real);
        return state();
    }

    /** OPS-04: while a build or test runs, source apply and revert are refused (editing and Save stay available). */
    void blockSourceWritesWhen(java.util.function.BooleanSupplier blocked) { this.sourceWritesBlocked = blocked; }

    synchronized ProjectProfile profile() { requireRoot(); return profile; }

    /** Identity of the sources a build/test checks: every project file outside build output and Workbench data. */
    synchronized String verificationFingerprint() throws IOException {
        requireRoot(); StringBuilder value = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = root.relativize(p);
                if (relative.getNameCount() > 0 && NOT_SOURCES.contains(relative.getName(0).toString())) continue;
                value.append(relative.toString().replace('\\', '/')).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
            }
        }
        return sha(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Saves only the outcome and source identity of a build/test, never its output (OPS-04, INT-05). */
    synchronized void recordVerification(String kind, String status, Integer exitCode, String fingerprint) throws IOException {
        requireRoot();
        var node = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        node.put("kind", kind); node.put("status", status); if (exitCode != null) node.put("exitCode", exitCode);
        node.put("at", java.time.Instant.now().toString()); node.put("sourceFingerprint", fingerprint);
        writeMetadata(VERIFICATION, tools.jackson.databind.json.JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        try { refreshPlan(); } catch (IOException | RuntimeException unavailable) { /* The plan is rewritten on the next Generate. */ }
    }

    /** The confirmed real root, or null before confirmation. */
    synchronized Path rootPath() { return root; }

    /** Current discovered bindings for the confirmed root. */
    synchronized List<Binding> bindings() throws IOException { requireRoot(); return discover(); }

    /** Root-confined path resolution for other Workbench services; links that escape the root are rejected. */
    synchronized Path resolveInRoot(String relative) throws IOException { return resolve(relative); }

    public synchronized WorkspaceState state() throws IOException {
        requireRoot();
        List<Binding> bindings = discover();
        return new WorkspaceState(root.toString(), profile, bindings, suggestedPackage(bindings),
                "src/main/resources/workflows", Files.exists(resolve(".jworkflow/workflow.jworkflow.json")),
                gitIgnoreState(), discoveryFingerprint, recovery, !store.backup().isEmpty());
    }

    public synchronized WorkspaceState refresh() throws IOException {
        requireRoot();
        String next = fingerprintSources();
        discoveryFingerprint = next;
        return state();
    }

    public synchronized boolean changed() throws IOException {
        requireRoot();
        return !fingerprintSources().equals(discoveryFingerprint);
    }

    public synchronized DraftView loadDraft() throws IOException {
        requireRoot(); rejectAdditionalWorkflows(); Path file = resolve(".jworkflow/workflow.jworkflow.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new DraftView("", "", "", "", "", "src/main/resources/workflows", 0, "", "{}");
        rejectLink(file); byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 2_097_152) throw new IOException("Workflow document exceeds 2 MiB.");
        var json = WorkflowGenerationService.workflowJson().readTree(bytes);
        if (json.path("schemaVersion").asInt() != 1) throw new IOException("Unknown workflow schema; original file was not changed.");
        return new DraftView(json.path("documentId").asText(), json.path("name").asText(), json.path("engineId").asText(),
                json.path("version").asText(), json.path("javaPackage").asText(), json.path("outputDirectory").asText("src/main/resources/workflows"), json.path("revision").asLong(), sha(bytes),
                WorkflowGenerationService.workflowJson().writeValueAsString(json.path("blockly")));
    }

    public synchronized DraftView saveDraft(SaveDraft request) throws IOException {
        requireRoot(); rejectAdditionalWorkflows(); Path directory = resolve(".jworkflow"); Files.createDirectories(directory); rejectLink(directory);
        Path file = resolve(".jworkflow/workflow.jworkflow.json");
        byte[] existing = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(file) : new byte[0];
        if (!Objects.equals(request.baseHash(), existing.length == 0 ? "" : sha(existing))) throw new Conflict("Draft changed outside Workbench. Reload before saving.");
        if (existing.length > 0) {
            var old = WorkflowGenerationService.workflowJson().readTree(existing);
            if (old.path("schemaVersion").asInt() != 1) throw new Conflict("Unknown workflow schema; original file was not changed.");
            if (WorkflowGenerationService.hasUnsupportedBlock(WorkflowGenerationService.workflowJson().writeValueAsString(old.path("blockly"))))
                throw new Conflict("Saved workflow contains an unsupported block; its original bytes were not changed.");
        }
        if (WorkflowGenerationService.hasUnsupportedBlock(request.blockly()))
            throw new IllegalArgumentException("Unsupported Blockly state cannot be saved by this Workbench version.");
        String id = request.documentId() == null || request.documentId().isBlank() ? UUID.randomUUID().toString() : request.documentId();
        long revision = request.revision() + 1;
        var mapper = WorkflowGenerationService.workflowJson();
        var document = mapper.createObjectNode();
        document.put("schemaVersion", 1); document.put("documentId", id); document.put("revision", revision);
        document.put("workbenchVersion", "0.1.0-SNAPSHOT"); document.put("coreProfile", "0.1.0-SNAPSHOT");
        document.put("name", value(request.name())); document.put("engineId", value(request.engineId()));
        document.put("version", value(request.version())); document.put("javaPackage", value(request.javaPackage()));
        document.put("outputDirectory", confinedRelative(request.outputDirectory()));
        document.set("blockly", mapper.readTree(request.blockly() == null || request.blockly().isBlank() ? "{}" : request.blockly()));
        // Compact on purpose: Blockly nests each chained block, so indentation grows quadratically with chain length
        // (a 500-block chain pretty-printed exceeds the 2 MiB limit while its content is about 100 KB).
        byte[] bytes = mapper.writeValueAsBytes(document);
        if (bytes.length > 2_097_152) throw new IllegalArgumentException("Workflow document exceeds 2 MiB.");
        Path temp = resolve(".jworkflow/workflow.jworkflow.json.tmp"); rejectLinkIfPresent(temp);
        Files.write(temp, bytes, StandardOpenOption.CREATE_NEW); rejectLinkIfPresent(file);
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        return loadDraft();
    }

    public synchronized WorkflowGenerationService.GenerationResult generate(GenerateRequest request) throws IOException {
        requireRoot();
        DraftView draft = loadDraft();
        if (draft.revision() != request.revision() || !Objects.equals(draft.hash(), request.hash()))
            throw new Conflict("Saved draft changed before generation. Save or reload and try again.");
        String safeName = draft.engineId().matches("[A-Za-z][A-Za-z0-9._-]{0,127}") ? draft.engineId() + ".groovy" : "invalid.groovy";
        Path target = resolve(draft.outputDirectory() + "/" + safeName);
        boolean exists = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        String hash = exists ? sha(Files.readAllBytes(target)) : "";
        List<Binding> bindings = discover();
        var result = new WorkflowGenerationService().generate(draft, bindings, exists, hash);
        writePlan(draft, result, result.state().equals("ready") ? integrate(draft, result.groovy(), bindings) : null);
        return result;
    }

    /** Whole-change-set proposal for the saved, successfully generated revision (INT-02). */
    public synchronized ChangeProposal proposeChanges(GenerateRequest request) throws IOException {
        requireRoot();
        DraftView draft = loadDraft();
        if (draft.revision() != request.revision() || !Objects.equals(draft.hash(), request.hash()))
            throw new Conflict("Saved draft changed after Generate. Generate again before reviewing source changes.");
        List<Binding> bindings = discover();
        var result = new WorkflowGenerationService().generate(draft, bindings, false, "");
        if (!result.state().equals("ready")) throw new Conflict("Generate must succeed before source changes can be proposed.");
        SourceIntegration.Result integration = integrate(draft, result.groovy(), bindings);
        List<ProposedFile> files = integration.changes().stream().map(c -> new ProposedFile(c.path(), c.before() == null ? "create" : c.after() == null ? "delete" : "update",
                c.beforeHash(), c.afterHash(), TextDiff.unified(c.path(), c.before(), c.after()))).toList();
        String token = token("apply", draft, integration.changes(), integration.manualGroovyEdit());
        return new ChangeProposal(token, draft.revision(), draft.hash(), discoveryFingerprint, profile, draft.javaPackage(), integration.groovyPath(),
                integration.manualGroovyEdit(), files, integration.artifacts(), integration.discrepancies());
    }

    /** Applies exactly the approved proposal, or nothing; a changed file, revision or discovery invalidates the token. */
    public synchronized ApplyResult applyChanges(ApplyRequest request) throws IOException {
        if (sourceWritesBlocked.getAsBoolean()) throw new Conflict("A build or test is running, so source changes are blocked until it finishes. Editing and Save remain available; nothing was queued.");
        ChangeProposal current = proposeChanges(new GenerateRequest(request.revision(), request.hash()));
        if (!current.token().equals(request.token())) throw new Conflict("The source proposal changed since you reviewed it. Review the new proposal.");
        if (current.manualGroovyEdit() && !request.replaceManualGroovy()) throw new Conflict("Confirm replacing the manually edited Groovy file before applying.");
        DraftView draft = loadDraft();
        SourceIntegration.Result integration = integrate(draft, new WorkflowGenerationService().generate(draft, discover(), false, "").groovy(), discover());
        if (integration.changes().isEmpty()) {
            recordApplied(draft, integration, false);
            refreshPlan();
            return new ApplyResult("already-applied", "The project already contains these sources; nothing was written.", plan());
        }
        store.apply("apply", integration.changes(), true);
        recordApplied(draft, integration, true);
        refreshPlan();
        return new ApplyResult("applied", "Applied " + integration.changes().size() + " file(s) for revision " + draft.revision() + ". Build and tests were not run.", plan());
    }

    /** Reverse diff of the latest applied change set; refused when any of its files changed afterwards (INT-03). */
    public synchronized ChangeProposal proposeRevert() throws IOException {
        requireRoot();
        List<ChangeSetStore.FileChange> reverse = reverseOfBackup();
        List<ProposedFile> files = reverse.stream().map(c -> new ProposedFile(c.path(), c.after() == null ? "delete" : c.before() == null ? "create" : "update",
                c.beforeHash(), c.afterHash(), TextDiff.unified(c.path(), c.before(), c.after()))).toList();
        return new ChangeProposal(token("revert", null, reverse, false), 0, "", discoveryFingerprint, profile, "", "", false, files, List.of(),
                List.of("Files created by the change set are deleted; files it updated return to their previous contents. Obsolete files from older changes are not touched."));
    }

    public synchronized ApplyResult applyRevert(String token) throws IOException {
        requireRoot();
        if (sourceWritesBlocked.getAsBoolean()) throw new Conflict("A build or test is running, so revert is blocked until it finishes. Nothing was queued.");
        List<ChangeSetStore.FileChange> reverse = reverseOfBackup();
        if (!token("revert", null, reverse, false).equals(token)) throw new Conflict("The revert proposal changed since you reviewed it. Review it again.");
        store.apply("revert", reverse, false);
        restorePreviousApplied();
        refreshPlan();
        return new ApplyResult("reverted", "Reverted " + reverse.size() + " file(s). No other revert point is kept.", plan());
    }

    public synchronized String plan() throws IOException {
        requireRoot(); Path file = resolve(IntegrationPlan.PATH);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private SourceIntegration.Result integrate(DraftView draft, String groovy, List<Binding> bindings) throws IOException {
        return SourceIntegration.build(draft, groovy, bindings, appliedHashes(), store::read);
    }

    private List<ChangeSetStore.FileChange> reverseOfBackup() throws IOException {
        List<ChangeSetStore.FileChange> backup = store.backup();
        if (backup.isEmpty()) throw new Conflict("There is no applied source change to revert.");
        List<String> edited = new ArrayList<>();
        for (var change : backup) if (!ChangeSetStore.FileChange.hash(store.read(change.path())).equals(change.afterHash())) edited.add(change.path());
        if (!edited.isEmpty()) throw new Conflict("Revert refused: these files changed after Workbench applied them: " + String.join(", ", edited) + ". Your edits were not touched.");
        return backup.reversed().stream().map(c -> new ChangeSetStore.FileChange(c.path(), c.after(), c.before())).toList();
    }

    private String token(String operation, DraftView draft, List<ChangeSetStore.FileChange> changes, boolean manual) {
        StringBuilder value = new StringBuilder(operation).append('\n');
        if (draft != null) value.append(draft.revision()).append('\n').append(draft.hash()).append('\n').append(draft.javaPackage()).append('\n').append(discoveryFingerprint).append('\n').append(profile).append('\n');
        value.append(manual).append('\n');
        for (var change : changes) value.append(change.path()).append('\0').append(change.beforeHash()).append('\0').append(change.afterHash()).append('\n');
        return sha(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> appliedHashes() throws IOException {
        var applied = readApplied(); Map<String, String> hashes = new LinkedHashMap<>();
        if (applied != null) applied.path("files").properties().forEach(e -> hashes.put(e.getKey(), e.getValue().asText()));
        return hashes;
    }

    private tools.jackson.databind.JsonNode readApplied() throws IOException {
        Path file = resolve(APPLIED);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readAllBytes(file)) : null;
    }

    /** Records what Workbench wrote, so later proposals can tell Workbench-owned files from user edits. */
    private void recordApplied(DraftView draft, SourceIntegration.Result integration, boolean keepPrevious) throws IOException {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var previous = readApplied();
        var node = mapper.createObjectNode();
        node.put("revision", draft.revision()); node.put("documentHash", draft.hash()); node.put("engineId", draft.engineId()); node.put("version", draft.version());
        var files = node.putObject("files");
        for (var artifact : integration.artifacts()) {
            if (artifact.status().equals("kept") || artifact.status().equals("not generated")) continue;
            String content = store.read(artifact.path());
            if (content != null) files.put(artifact.path(), ChangeSetStore.FileChange.hash(content));
        }
        // One level of history: what a revert of this change set restores.
        var prior = previous == null ? null : keepPrevious ? previous : previous.path("previous");
        if (prior != null && prior.isObject()) {
            var copy = (tools.jackson.databind.node.ObjectNode) prior.deepCopy(); copy.remove("previous"); node.set("previous", copy);
        }
        writeMetadata(APPLIED, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    private void restorePreviousApplied() throws IOException {
        var applied = readApplied(); Path file = resolve(APPLIED);
        if (applied == null) return;
        if (applied.path("previous").isObject()) writeMetadata(APPLIED, tools.jackson.databind.json.JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(applied.path("previous")));
        else Files.deleteIfExists(file);
    }

    private void refreshPlan() throws IOException {
        DraftView draft = loadDraft(); List<Binding> bindings = discover();
        var result = new WorkflowGenerationService().generate(draft, bindings, false, "");
        writePlan(draft, result, result.state().equals("ready") ? integrate(draft, result.groovy(), bindings) : null);
    }

    private void writePlan(DraftView draft, WorkflowGenerationService.GenerationResult result, SourceIntegration.Result integration) throws IOException {
        var applied = readApplied();
        IntegrationPlan.Applied summary = null;
        if (applied != null) {
            LinkedHashSet<String> engineIds = new LinkedHashSet<>(), paths = new LinkedHashSet<>();
            for (var record : List.of(applied, applied.path("previous"))) {
                if (!record.isObject()) continue;
                engineIds.add(record.path("engineId").asText()); paths.addAll(record.path("files").propertyNames());
            }
            summary = new IntegrationPlan.Applied(applied.path("revision").asLong(), applied.path("engineId").asText(), applied.path("version").asText(),
                    List.copyOf(engineIds), List.copyOf(paths));
        }
        IntegrationPlan.Verification verification = null;
        Path verificationFile = resolve(VERIFICATION);
        if (Files.isRegularFile(verificationFile, LinkOption.NOFOLLOW_LINKS)) {
            var v = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readAllBytes(verificationFile));
            verification = new IntegrationPlan.Verification(v.path("kind").asText(), v.path("status").asText(), v.path("at").asText(),
                    v.path("sourceFingerprint").asText().equals(verificationFingerprint()));
        }
        writeMetadata(IntegrationPlan.PATH, IntegrationPlan.render(draft, profile, result, integration, summary, verification).getBytes(StandardCharsets.UTF_8));
    }

    private void writeMetadata(String relative, byte[] bytes) throws IOException {
        Path directory = resolve(".jworkflow"); Files.createDirectories(directory); rejectLink(directory);
        Path file = resolve(relative); rejectLinkIfPresent(file);
        Path temp = resolve(relative + ".tmp"); rejectLinkIfPresent(temp); Files.deleteIfExists(temp);
        Files.write(temp, bytes, StandardOpenOption.CREATE_NEW);
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    public synchronized GitIgnoreProposal proposeGitIgnore() throws IOException {
        requireRoot(); Path file = resolve(".gitignore"); String before = Files.exists(file) ? Files.readString(file) : "";
        boolean present = before.lines().map(String::strip).anyMatch(".jworkflow/"::equals);
        String newline = before.contains("\r\n") ? "\r\n" : "\n";
        String after = present ? before : before + (before.isEmpty() || before.endsWith("\n") ? "" : newline) + ".jworkflow/" + newline;
        return new GitIgnoreProposal(before, after, sha(before.getBytes(StandardCharsets.UTF_8)), present);
    }

    public synchronized void applyGitIgnore(String expectedHash) throws IOException {
        GitIgnoreProposal proposal = proposeGitIgnore();
        if (!Objects.equals(expectedHash, proposal.beforeHash())) throw new Conflict(".gitignore changed; review the new proposal.");
        if (proposal.alreadyPresent()) return;
        Path file = resolve(".gitignore"); rejectLinkIfPresent(file); Files.writeString(file, proposal.after(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private ProjectProfile inspect(Path candidate) throws IOException {
        boolean pom = Files.isRegularFile(candidate.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
        boolean gradle = Files.isRegularFile(candidate.resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS);
        boolean kotlin = Files.isRegularFile(candidate.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS);
        if ((pom ? 1 : 0) + (gradle ? 1 : 0) + (kotlin ? 1 : 0) != 1) throw new IllegalArgumentException("Project must contain exactly one of pom.xml, build.gradle, or build.gradle.kts.");
        String build = Files.readString(candidate.resolve(pom ? "pom.xml" : gradle ? "build.gradle" : "build.gradle.kts"));
        if (pom && build.matches("(?s).*<modules>.*")) throw new IllegalArgumentException("Multi-module Maven projects are not supported.");
        String kind = pom ? "Maven" : kotlin ? "Gradle Kotlin" : "Gradle Groovy";
        int java = detectJava(build); String core = detectCore(build);
        if (core.startsWith("${") && core.endsWith("}")) { String key = core.substring(2, core.length() - 1); Matcher property = Pattern.compile("<" + Pattern.quote(key) + ">\\s*([^<]+)\\s*</" + Pattern.quote(key) + ">").matcher(build); core = property.find() ? property.group(1).strip() : ""; }
        if (java != 0 && java < 17) throw new IllegalArgumentException("Configured Java target " + java + " is below the Java 17 minimum.");
        if (!core.isEmpty() && !"0.1.0-SNAPSHOT".equals(core)) throw new IllegalArgumentException("Project uses jworkflow-core " + core + "; expected 0.1.0-SNAPSHOT.");
        return new ProjectProfile(kind, java == 0 ? "unresolved" : Integer.toString(java), core.isEmpty() ? "unresolved" : core);
    }

    private static int detectJava(String build) {
        for (String regex : List.of("<maven.compiler.release>\\s*(\\d+)", "<java.version>\\s*(\\d+)", "JavaVersion.VERSION_(\\d+)", "languageVersion\\s*=\\s*JavaLanguageVersion.of\\((\\d+)\\)", "sourceCompatibility\\s*=.*?(\\d+)")) {
            Matcher m = Pattern.compile(regex).matcher(build); if (m.find()) return Integer.parseInt(m.group(1));
        } return 0;
    }
    private static String detectCore(String build) {
        Matcher direct = Pattern.compile("org\\.jworkflow[:\"']+jworkflow-core[:\"']+([\\w.-]+)").matcher(build); if (direct.find()) return direct.group(1);
        if (build.contains("<artifactId>jworkflow-core</artifactId>")) { Matcher m = Pattern.compile("<artifactId>jworkflow-core</artifactId>\\s*<version>([^<]+)</version>").matcher(build); if (m.find()) return m.group(1).strip(); }
        return "";
    }

    private List<Binding> discover() throws IOException {
        Path sources = resolve("src/main/java"); if (!Files.isDirectory(sources)) return List.of();
        List<Binding> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sources)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (!path.toRealPath().startsWith(root) || Files.size(path) > MAX_SOURCE_BYTES) continue;
                String source = Files.readString(path); String pkg = match(PACKAGE, source, 1); Matcher type = TYPE.matcher(source);
                while (type.find()) {
                    String name = type.group(2), implemented = Objects.toString(type.group(3), "");
                    String role = implemented.contains("Command") ? "command" : name.endsWith("Event") ? "event" : name.endsWith("Listener") ? "listener" : "";
                    if (!role.isEmpty()) result.add(new Binding(role, pkg, name, fields(source, type.group(1)), root.relativize(path).toString().replace('\\', '/'), line(source, type.start()), role.equals("listener") ? "Listener should translate an event into a command." : ""));
                }
            }
        } return List.copyOf(result);
    }
    private static List<Field> fields(String source, String kind) {
        if (!"record".equals(kind)) return List.of(); Matcher m = RECORD_FIELDS.matcher(source); if (!m.find() || m.group(1).isBlank()) return List.of();
        List<Field> fields = new ArrayList<>();
        for (String part : topLevelComponents(m.group(1))) {
            // Record component annotations do not belong to the type; generic arguments keep their commas.
            String declaration = part.replaceAll("@[\\w.]+(\\([^)]*\\))?", " ").strip();
            int split = declaration.lastIndexOf(' ');
            if (split > 0) fields.add(new Field(declaration.substring(0, split).replaceAll("\\s+", " ").replaceAll("\\s*([<>,])\\s*", "$1").replace(",", ", "), declaration.substring(split + 1)));
        }
        return fields;
    }
    private static List<String> topLevelComponents(String components) {
        List<String> parts = new ArrayList<>(); int depth = 0, start = 0;
        for (int i = 0; i < components.length(); i++) {
            char c = components.charAt(i);
            if (c == '<') depth++; else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { parts.add(components.substring(start, i)); start = i + 1; }
        }
        parts.add(components.substring(start));
        return parts.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
    }
    private String fingerprintSources() throws IOException { Path sources = resolve("src/main/java"); if (!Files.exists(sources)) return "empty"; StringBuilder value = new StringBuilder(); try (Stream<Path> paths = Files.walk(sources)) { for (Path p : paths.filter(x -> x.toString().endsWith(".java")).sorted().toList()) value.append(root.relativize(p)).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';'); } return sha(value.toString().getBytes(StandardCharsets.UTF_8)); }
    private Path resolve(String relative) throws IOException { requireRoot(); Path path = root.resolve(relative).normalize(); if (!path.startsWith(root)) throw new IOException("Path escapes confirmed project root."); Path parent = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? path.toRealPath() : nearestExisting(path); if (!parent.startsWith(root)) throw new IOException("Resolved path escapes confirmed project root."); return path; }
    private String confinedRelative(String relative) throws IOException { if (relative == null || relative.isBlank()) throw new IllegalArgumentException("Confirm an in-project workflow output directory."); Path absolute = resolve(relative); return root.relativize(absolute).toString().replace('\\', '/'); }
    private Path nearestExisting(Path path) throws IOException { Path current = path; while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) current = current.getParent(); return current == null ? root : current.toRealPath(); }
    private void requireRoot() { if (root == null) throw new IllegalStateException("Confirm a project root first."); }
    private static void rejectLink(Path path) throws IOException { if (Files.isSymbolicLink(path)) throw new IOException("Linked paths are not allowed: " + path.getFileName()); }
    private static void rejectLinkIfPresent(Path path) throws IOException { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) rejectLink(path); }
    /** One workflow per project: never choose silently among unexpected extra documents (DOC-03). */
    private void rejectAdditionalWorkflows() throws IOException {
        Path directory = resolve(".jworkflow"); if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> files = Files.list(directory)) {
            List<String> extra = files.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jworkflow.json") && !n.equals("workflow.jworkflow.json")).sorted().toList();
            if (!extra.isEmpty()) throw new Conflict("Workbench supports one workflow per project, but .jworkflow/ also contains " + String.join(", ", extra) + ". Move or remove the extra documents; nothing was changed.");
        }
    }
    private String gitIgnoreState() throws IOException { GitIgnoreProposal p = proposeGitIgnore(); return p.alreadyPresent() ? "included" : "not included"; }
    private static String suggestedPackage(List<Binding> values) { return values.stream().map(Binding::packageName).filter(v -> !v.isBlank()).collect(java.util.stream.Collectors.groupingBy(v -> v, LinkedHashMap::new, java.util.stream.Collectors.counting())).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(""); }
    private static String match(Pattern pattern, String source, int group) { Matcher m = pattern.matcher(source); return m.find() ? m.group(group) : ""; }
    private static int line(String source, int position) { return 1 + (int) source.substring(0, position).chars().filter(c -> c == '\n').count(); }
    private static String value(String value) { return value == null ? "" : value; }
    static String sha(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }

    public record ProjectProfile(String buildSystem, String javaTarget, String coreVersion) {}
    public record Field(String type, String name) {}
    public record Binding(String role, String packageName, String name, List<Field> fields, String source, int line, String proposal) {}
    public record WorkspaceState(String root, ProjectProfile profile, List<Binding> bindings, String suggestedPackage, String outputDirectory, boolean draftExists, String gitIgnore, String discoveryRevision,
            String recovery, boolean revertAvailable) {}
    public record ProposedFile(String path, String change, String beforeHash, String afterHash, String diff) {}
    public record ChangeProposal(String token, long revision, String documentHash, String discoveryRevision, ProjectProfile profile, String javaPackage, String groovyPath,
            boolean manualGroovyEdit, List<ProposedFile> files, List<SourceIntegration.Artifact> artifacts, List<String> discrepancies) {}
    public record ApplyRequest(String token, long revision, String hash, boolean replaceManualGroovy) {}
    public record ApplyResult(String status, String message, String plan) {}
    public record SaveDraft(String documentId, String name, String engineId, String version, String javaPackage, String outputDirectory, long revision, String baseHash, String blockly) {}
    public record DraftView(String documentId, String name, String engineId, String version, String javaPackage, String outputDirectory, long revision, String hash, String blockly) {}
    public record GenerateRequest(long revision, String hash) {}
    public record GitIgnoreProposal(String before, String after, String beforeHash, boolean alreadyPresent) {}
    public static final class Conflict extends IOException { public Conflict(String message) { super(message); } }
}
