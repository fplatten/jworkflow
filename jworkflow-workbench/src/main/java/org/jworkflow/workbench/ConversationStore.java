package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persistent conversations under {@code .jworkflow/conversations/} (CHAT-01). History is kept until the user deletes
 * it; nothing is pruned. Deleted IDs are remembered for the session so late callbacks cannot recreate them. Messages
 * marked transient (diagnostic excerpts, AI-05) are never written.
 */
final class ConversationStore {
    static final String DIRECTORY = ".jworkflow/conversations";
    private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final ChangeSetStore.Resolver resolver;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();

    /** {@code status} is complete, incomplete (deadline reached) or failed; {@code proposal} summarizes a block proposal. */
    record Message(String role, String text, String status, String proposal, boolean transientContext) {
        static Message user(String text) { return new Message("user", text, "complete", "", false); }
    }
    record Conversation(String id, String title, String createdAt, String updatedAt, List<Message> messages) {
        static Conversation fresh() { String now = java.time.Instant.now().toString(); return new Conversation(UUID.randomUUID().toString(), "", now, now, List.of()); }
        Conversation with(Message message) {
            List<Message> next = new ArrayList<>(messages); next.add(message);
            String nextTitle = title.isBlank() && message.role().equals("user") ? message.text().strip().replaceAll("\\s+", " ") : title;
            return new Conversation(id, nextTitle.length() > 60 ? nextTitle.substring(0, 60) + "…" : nextTitle, createdAt, java.time.Instant.now().toString(), List.copyOf(next));
        }
    }
    record Summary(String id, String title, String updatedAt, int messages) {}

    ConversationStore(ChangeSetStore.Resolver resolver) { this.resolver = resolver; }

    List<Summary> list() throws IOException {
        Path directory = resolver.resolve(DIRECTORY);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Summary> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches(ID + "\\.json")).toList()) {
                try { var c = load(file.getFileName().toString().replace(".json", "")); result.add(new Summary(c.id(), c.title(), c.updatedAt(), c.messages().size())); }
                catch (IOException | RuntimeException unreadable) { /* A damaged file is skipped, never rewritten. */ }
            }
        }
        result.sort(Comparator.comparing(Summary::updatedAt).reversed());
        return result;
    }

    Conversation load(String id) throws IOException {
        Path file = file(id);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Conversation " + id + " was not found.");
        if (Files.size(file) > 8 * 1024 * 1024) throw new IOException("Conversation " + id + " exceeds 8 MiB and was not loaded.");
        return json.readValue(Files.readAllBytes(file), Conversation.class);
    }

    /** Writes the conversation unless it was deleted meanwhile; returns false when suppressed. */
    synchronized boolean save(Conversation conversation) throws IOException {
        if (deleted.contains(conversation.id()) || conversation.messages().isEmpty()) return false;
        Conversation durable = new Conversation(conversation.id(), conversation.title(), conversation.createdAt(), conversation.updatedAt(),
                conversation.messages().stream().filter(m -> !m.transientContext()).toList());
        Path directory = resolver.resolve(DIRECTORY);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Linked conversation directory is not allowed.");
        Path file = file(conversation.id()), temp = resolver.resolve(DIRECTORY + "/" + conversation.id() + ".json.tmp");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temp)) throw new IOException("Linked conversation files are not allowed.");
        Files.write(temp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(durable));
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        return true;
    }

    synchronized void delete(String id) throws IOException {
        deleted.add(id);
        Path file = file(id);
        if (Files.isSymbolicLink(file)) throw new IOException("Linked conversation files are not allowed.");
        Files.deleteIfExists(file);
    }

    private Path file(String id) throws IOException {
        if (id == null || !id.matches(ID)) throw new IllegalArgumentException("Invalid conversation ID.");
        return resolver.resolve(DIRECTORY + "/" + id + ".json");
    }
}
