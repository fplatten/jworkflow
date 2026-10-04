package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Journaled multi-file writes below the confirmed root. Every change is staged with its original bytes first;
 * any failure rolls all files back, and an interrupted journal is recovered before the next operation (INT-02/03).
 * Only the latest successfully applied change set is kept as the revert point.
 */
final class ChangeSetStore {
    static final String JOURNAL = ".jworkflow/journal";
    static final String BACKUP = ".jworkflow/backup";
    private static final String BACKUP_NEXT = ".jworkflow/backup.next";
    private final Resolver resolver;
    private final JsonMapper json = JsonMapper.builder().build();
    /** Test seam for fault injection before each target write; production uses a no-op. */
    WriteHook hook = (index, path) -> {};

    ChangeSetStore(Resolver resolver) { this.resolver = resolver; }

    /** One file in a change set; {@code before}/{@code after} null means the file is absent on that side. */
    record FileChange(String path, String before, String after) {
        String beforeHash() { return hash(before); }
        String afterHash() { return hash(after); }
        static String hash(String content) { return content == null ? "" : ProjectWorkspace.sha(content.getBytes(StandardCharsets.UTF_8)); }
    }

    @FunctionalInterface interface Resolver { Path resolve(String relative) throws IOException; }
    @FunctionalInterface interface WriteHook { void beforeWrite(int index, String path) throws IOException; }

    /** Current content of a root-relative file, or null when absent. Links are refused. */
    String read(String relative) throws IOException {
        Path file = resolver.resolve(relative);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + relative);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Applies every change or none. {@code keepAsBackup} true records this set as the latest revert point; false
     * (a revert) clears the revert point after success.
     */
    void apply(String operation, List<FileChange> changes, boolean keepAsBackup) throws IOException {
        String pending = recover();
        if (pending != null) throw new ProjectWorkspace.Conflict(pending);
        for (FileChange change : changes)
            if (!Objects.equals(FileChange.hash(read(change.path())), change.beforeHash()))
                throw new ProjectWorkspace.Conflict(change.path() + " changed since the proposal was created. Review the new proposal.");
        writeJournal(operation, changes, "applying");
        int index = 0;
        try {
            for (FileChange change : changes) { hook.beforeWrite(index++, change.path()); write(change.path(), change.after()); }
        } catch (IOException | RuntimeException failure) {
            String recovery = rollback();
            if (recovery != null) throw new IOException("Applying failed (" + message(failure) + ") and recovery is incomplete: " + recovery, failure);
            throw new IOException("Applying failed (" + message(failure) + "); every file was restored to its previous contents.", failure);
        }
        markCommitted();
        if (keepAsBackup) stageBackup(operation, changes);
        finish(keepAsBackup);
    }

    /** The latest successfully applied change set, or an empty list. */
    List<FileChange> backup() throws IOException {
        Path manifest = resolver.resolve(BACKUP + "/manifest.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return List.of();
        return readChanges(BACKUP, json.readTree(Files.readAllBytes(manifest)));
    }

    /**
     * Completes or rolls back an interrupted journal. Returns null when nothing is pending afterwards, otherwise a
     * message explaining why the journal was kept for the user.
     */
    String recover() throws IOException {
        Path manifest = resolver.resolve(JOURNAL + "/journal.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonNode journal = json.readTree(Files.readAllBytes(manifest));
        if ("committed".equals(journal.path("state").asText())) { finish(journal.path("keepAsBackup").asBoolean()); return null; }
        return rollback();
    }

    private String rollback() throws IOException {
        Path manifest = resolver.resolve(JOURNAL + "/journal.json");
        List<FileChange> changes = readChanges(JOURNAL, json.readTree(Files.readAllBytes(manifest)));
        List<String> unexpected = new ArrayList<>();
        for (FileChange change : changes) {
            String current = FileChange.hash(read(change.path()));
            if (!current.equals(change.beforeHash()) && !current.equals(change.afterHash())) unexpected.add(change.path());
        }
        if (!unexpected.isEmpty()) return "files changed outside Workbench during an interrupted operation: " + String.join(", ", unexpected)
                + ". The journal in " + JOURNAL + " was kept; restore or remove those files manually.";
        for (FileChange change : changes.reversed())
            if (!FileChange.hash(read(change.path())).equals(change.beforeHash())) write(change.path(), change.before());
        deleteTree(JOURNAL);
        return null;
    }

    private void writeJournal(String operation, List<FileChange> changes, String state) throws IOException {
        deleteTree(JOURNAL);
        Path directory = resolver.resolve(JOURNAL);
        Files.createDirectories(directory);
        writeChanges(JOURNAL, changes, operation, state, false);
    }

    private void markCommitted() throws IOException {
        Path manifest = resolver.resolve(JOURNAL + "/journal.json");
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(manifest));
        node.put("state", "committed");
        writeAtomically(manifest, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    private void stageBackup(String operation, List<FileChange> changes) throws IOException {
        deleteTree(BACKUP_NEXT);
        Files.createDirectories(resolver.resolve(BACKUP_NEXT));
        writeChanges(BACKUP_NEXT, changes, operation, "applied", true);
        Path manifest = resolver.resolve(JOURNAL + "/journal.json");
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(manifest));
        node.put("keepAsBackup", true);
        writeAtomically(manifest, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
    }

    /** Promotes the staged revert point (or clears it after a revert) and removes the journal. */
    private void finish(boolean keepAsBackup) throws IOException {
        Path next = resolver.resolve(BACKUP_NEXT);
        if (keepAsBackup) {
            if (Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) { deleteTree(BACKUP); Files.move(next, resolver.resolve(BACKUP)); }
        } else { deleteTree(BACKUP_NEXT); deleteTree(BACKUP); }
        deleteTree(JOURNAL);
    }

    private void writeChanges(String directory, List<FileChange> changes, String operation, String state, boolean keepAsBackup) throws IOException {
        var root = json.createObjectNode();
        root.put("id", UUID.randomUUID().toString()); root.put("operation", operation); root.put("state", state); root.put("keepAsBackup", keepAsBackup);
        var items = root.putArray("changes");
        for (int i = 0; i < changes.size(); i++) {
            FileChange change = changes.get(i);
            var item = items.addObject();
            item.put("path", change.path()); item.put("beforeHash", change.beforeHash()); item.put("afterHash", change.afterHash());
            item.put("beforeExists", change.before() != null); item.put("afterExists", change.after() != null);
            if (change.before() != null) Files.writeString(resolver.resolve(directory + "/before-" + i), change.before(), StandardCharsets.UTF_8);
            if (change.after() != null) Files.writeString(resolver.resolve(directory + "/after-" + i), change.after(), StandardCharsets.UTF_8);
        }
        writeAtomically(resolver.resolve(directory + "/" + (directory.equals(JOURNAL) ? "journal.json" : "manifest.json")), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    private List<FileChange> readChanges(String directory, JsonNode root) throws IOException {
        List<FileChange> changes = new ArrayList<>();
        int i = 0;
        for (JsonNode item : root.path("changes")) {
            String before = item.path("beforeExists").asBoolean() ? Files.readString(resolver.resolve(directory + "/before-" + i), StandardCharsets.UTF_8) : null;
            String after = item.path("afterExists").asBoolean() ? Files.readString(resolver.resolve(directory + "/after-" + i), StandardCharsets.UTF_8) : null;
            FileChange change = new FileChange(item.path("path").asText(), before, after);
            if (!change.beforeHash().equals(item.path("beforeHash").asText()) || !change.afterHash().equals(item.path("afterHash").asText()))
                throw new IOException("Stored copy of " + change.path() + " in " + directory + " is damaged; it was not used.");
            changes.add(change); i++;
        }
        return changes;
    }

    private void write(String relative, String content) throws IOException {
        Path target = resolver.resolve(relative);
        if (Files.isSymbolicLink(target)) throw new IOException("Linked paths are not allowed: " + relative);
        if (content == null) { Files.deleteIfExists(target); return; }
        Files.createDirectories(target.getParent());
        // Recheck after creating parents so a link swapped in meanwhile cannot redirect the write (SEC-01).
        target = resolver.resolve(relative);
        writeAtomically(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".jworkflow-tmp");
        Files.deleteIfExists(temp);
        Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC);
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void deleteTree(String relative) throws IOException {
        Path directory = resolver.resolve(relative);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory)) throw new IOException("Linked paths are not allowed: " + relative);
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
}
