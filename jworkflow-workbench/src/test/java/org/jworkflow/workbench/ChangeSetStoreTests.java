package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** INT-02/INT-03: all-or-nothing writes, interrupted-journal recovery and a single latest revert point. */
class ChangeSetStoreTests {
    @TempDir Path root;

    private ChangeSetStore store() {
        return new ChangeSetStore(relative -> {
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root)) throw new IOException("escapes root");
            return path;
        });
    }

    private List<ChangeSetStore.FileChange> changes() throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Existing.java"), "old\n");
        return List.of(new ChangeSetStore.FileChange("src/Existing.java", "old\n", "new\n"),
                new ChangeSetStore.FileChange("src/a/Created.java", null, "created\n"),
                new ChangeSetStore.FileChange("src/b/Second.java", null, "second\n"));
    }

    @Test void appliesEverythingAndKeepsOnlyTheLatestRevertPoint() throws Exception {
        var store = store(); var changes = changes();
        store.apply("apply", changes, true);
        assertEquals("new\n", Files.readString(root.resolve("src/Existing.java")));
        assertEquals("created\n", Files.readString(root.resolve("src/a/Created.java")));
        assertEquals(changes, store.backup());
        assertFalse(Files.exists(root.resolve(ChangeSetStore.JOURNAL)));
        var next = List.of(new ChangeSetStore.FileChange("src/Existing.java", "new\n", "newer\n"));
        store.apply("apply", next, true);
        assertEquals(next, store.backup());
        store.apply("revert", List.of(new ChangeSetStore.FileChange("src/Existing.java", "newer\n", "new\n")), false);
        assertEquals(List.of(), store.backup());
    }

    @Test void failureBeforeAnyWriteRestoresEveryFile() throws Exception {
        for (int failAt = 0; failAt < 3; failAt++) {
            for (var path : List.of("src", ".jworkflow")) deleteTree(root.resolve(path));
            var store = store(); var changes = changes(); int target = failAt;
            store.hook = (index, path) -> { if (index == target) throw new IOException("disk full"); };
            var failure = assertThrows(IOException.class, () -> store.apply("apply", changes, true));
            assertTrue(failure.getMessage().contains("every file was restored"), failure.getMessage());
            assertEquals("old\n", Files.readString(root.resolve("src/Existing.java")), "fail at " + failAt);
            assertFalse(Files.exists(root.resolve("src/a/Created.java")), "fail at " + failAt);
            assertFalse(Files.exists(root.resolve("src/b/Second.java")), "fail at " + failAt);
            assertFalse(Files.exists(root.resolve(ChangeSetStore.JOURNAL)));
            assertEquals(List.of(), store.backup());
        }
    }

    @Test void interruptedProcessIsRolledBackOnRecovery() throws Exception {
        var crashing = store(); var changes = changes();
        // An Error is not handled by apply, so the journal and the first written file stay behind as after a crash.
        crashing.hook = (index, path) -> { if (index == 1) throw new StackOverflowError("simulated process death"); };
        assertThrows(StackOverflowError.class, () -> crashing.apply("apply", changes, true));
        assertEquals("new\n", Files.readString(root.resolve("src/Existing.java")));
        assertTrue(Files.exists(root.resolve(ChangeSetStore.JOURNAL + "/journal.json")));
        assertNull(store().recover());
        assertEquals("old\n", Files.readString(root.resolve("src/Existing.java")));
        assertFalse(Files.exists(root.resolve("src/a/Created.java")));
        assertFalse(Files.exists(root.resolve(ChangeSetStore.JOURNAL)));
    }

    @Test void recoveryKeepsTheJournalWhenAFileWasEditedMeanwhile() throws Exception {
        var crashing = store(); var changes = changes();
        crashing.hook = (index, path) -> { if (index == 1) throw new StackOverflowError("simulated process death"); };
        assertThrows(StackOverflowError.class, () -> crashing.apply("apply", changes, true));
        Files.writeString(root.resolve("src/Existing.java"), "user edit\n");
        String message = store().recover();
        assertNotNull(message); assertTrue(message.contains("src/Existing.java"), message);
        assertEquals("user edit\n", Files.readString(root.resolve("src/Existing.java")));
        assertTrue(Files.exists(root.resolve(ChangeSetStore.JOURNAL + "/journal.json")));
        assertThrows(ProjectWorkspace.Conflict.class, () -> store().apply("apply", List.of(), true));
    }

    @Test void refusesStaleBeforeContentWithoutWriting() throws Exception {
        var store = store(); var changes = changes();
        Files.writeString(root.resolve("src/Existing.java"), "changed meanwhile\n");
        assertThrows(ProjectWorkspace.Conflict.class, () -> store.apply("apply", changes, true));
        assertFalse(Files.exists(root.resolve("src/a/Created.java")));
        assertFalse(Files.exists(root.resolve(ChangeSetStore.JOURNAL)));
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) { for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
}
