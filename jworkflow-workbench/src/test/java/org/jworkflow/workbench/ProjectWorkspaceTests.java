package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorkspaceTests {
    @TempDir Path temp;

    @Test void confirmsMavenDiscoversBindingsSavesDraftAndDetectsConflict() throws Exception {
        Path root = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.jworkflow</groupId><artifactId>jworkflow-core</artifactId><version>0.1.0-SNAPSHOT</version></dependency></dependencies></project>");
        source(root, "example/OrderCreatedEvent.java", "package example; public record OrderCreatedEvent(String orderId, long amount) {}");
        source(root, "example/CreateOrder.java", "package example; import org.jworkflow.application.Command; public record CreateOrder(String orderId) implements Command {}");
        source(root, "example/OrderListener.java", "package example; public final class OrderListener {}");
        var workspace = workspace(); var state = workspace.confirm(root.toString());
        assertEquals("Maven", state.profile().buildSystem()); assertEquals("17", state.profile().javaTarget()); assertEquals(3, state.bindings().size()); assertEquals("example", state.suggestedPackage());
        var saved = workspace.saveDraft(new ProjectWorkspace.SaveDraft("", "", "", "", "example", "src/main/resources/workflows", 0, "", "{\"blocks\":{\"languageVersion\":0,\"blocks\":[]}}"));
        assertEquals(1, saved.revision()); assertFalse(saved.documentId().isBlank()); assertEquals("example", workspace.loadDraft().javaPackage());
        Files.writeString(root.resolve(".jworkflow/workflow.jworkflow.json"), " ", StandardOpenOption.APPEND);
        assertThrows(ProjectWorkspace.Conflict.class, () -> workspace.saveDraft(new ProjectWorkspace.SaveDraft(saved.documentId(), "x", "x", "1", "example", "src/main/resources/workflows", saved.revision(), saved.hash(), "{}")));
    }

    @Test void supportsBothGradleDslAndRefreshesWithoutExecutingBuilds() throws Exception {
        for (String build : new String[]{"build.gradle", "build.gradle.kts"}) {
            Path root = temp.resolve(build.replace('.', '-')); Files.createDirectories(root);
            Files.writeString(root.resolve(build), "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\ndependencies { implementation(\"org.jworkflow:jworkflow-core:0.1.0-SNAPSHOT\") }");
            source(root, "demo/InitialEvent.java", "package demo; public record InitialEvent(String id) {}");
            var workspace = workspace(); var first = workspace.confirm(root.toString());
            assertEquals(build.endsWith("kts") ? "Gradle Kotlin" : "Gradle Groovy", first.profile().buildSystem());
            String revision = first.discoveryRevision(); Thread.sleep(5);
            source(root, "demo/NextEvent.java", "package demo; public record NextEvent(String id) {}");
            assertTrue(workspace.changed()); var refreshed = workspace.refresh(); assertNotEquals(revision, refreshed.discoveryRevision()); assertEquals(2, refreshed.bindings().size());
        }
    }

    @Test void rejectsUnsupportedRootsAndAppliesOnlyReviewedGitignore() throws Exception {
        Path old = project("build.gradle", "sourceCompatibility = JavaVersion.VERSION_11");
        assertThrows(IllegalArgumentException.class, () -> workspace().confirm(old.toString()));
        Path multi = temp.resolve("multi"); Files.createDirectories(multi); Files.writeString(multi.resolve("pom.xml"), "<project><modules><module>a</module></modules></project>");
        assertThrows(IllegalArgumentException.class, () -> workspace().confirm(multi.toString()));
        Path valid = project("build.gradle", "sourceCompatibility = JavaVersion.VERSION_17"); var workspace = workspace(); workspace.confirm(valid.toString());
        var proposal = workspace.proposeGitIgnore(); assertTrue(proposal.after().contains(".jworkflow/"));
        Files.writeString(valid.resolve(".gitignore"), "target/\n"); String staleHash = proposal.beforeHash();
        assertThrows(ProjectWorkspace.Conflict.class, () -> workspace.applyGitIgnore(staleHash));
        proposal = workspace.proposeGitIgnore(); workspace.applyGitIgnore(proposal.beforeHash()); assertEquals(1, Files.readString(valid.resolve(".gitignore")).lines().filter(".jworkflow/"::equals).count());
    }

    @Test void preservesFutureBlocksByRefusingUnsafeRewrite() throws Exception {
        Path root = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        var workspace = workspace(); workspace.confirm(root.toString());
        Path directory = root.resolve(".jworkflow"); Files.createDirectories(directory);
        String original = "{\"schemaVersion\":1,\"documentId\":\"future\",\"revision\":1,\"workbenchVersion\":\"future\",\"coreProfile\":\"0.1.0-SNAPSHOT\",\"name\":\"x\",\"engineId\":\"x\",\"version\":\"1\",\"javaPackage\":\"\",\"outputDirectory\":\"src/main/resources/workflows\",\"blockly\":{\"blocks\":{\"blocks\":[{\"type\":\"future_block\",\"id\":\"f\"}]}}}";
        Path document = directory.resolve("workflow.jworkflow.json"); Files.writeString(document, original);
        assertThrows(ProjectWorkspace.Conflict.class, () -> workspace.saveDraft(new ProjectWorkspace.SaveDraft("future", "x", "x", "1", "", "src/main/resources/workflows", 1, ProjectWorkspace.sha(original.getBytes()), "{}")));
        assertEquals(original, Files.readString(document));
    }

    @Test void rootReachedThroughLinkedParentStillDiscoversSources() throws Exception {
        // macOS temp and /tmp paths sit below /var -> /private/var style links.
        Path real = Files.createDirectories(temp.resolve("real")); Path alias = link(temp.resolve("alias"), real);
        Path root = Files.createDirectories(real.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        source(root, "demo/LinkedEvent.java", "package demo; public record LinkedEvent(String id) {}");
        var state = workspace().confirm(alias.resolve("project").toString());
        assertEquals(root.toRealPath().toString(), state.root()); assertEquals(1, state.bindings().size());
    }

    @Test void rejectsInRootLinksThatEscapeTheProject() throws Exception {
        Path root = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createDirectories(root.resolve("src/main/resources"));
        link(root.resolve("src/main/resources/workflows"), outside);
        var workspace = workspace(); workspace.confirm(root.toString());
        var escaping = new ProjectWorkspace.SaveDraft("", "x", "x", "1", "demo", "src/main/resources/workflows", 0, "", "{}");
        IOException failure = assertThrows(IOException.class, () -> workspace.saveDraft(escaping));
        assertTrue(failure.getMessage().contains("escapes"), failure.getMessage());
        assertFalse(Files.exists(outside.resolve("workflow.jworkflow.json")));
    }

    @Test void rejectsLinkedGitignoreOutsideTheProject() throws Exception {
        Path root = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        Path secret = Files.writeString(Files.createDirectories(temp.resolve("outside-file")).resolve("secret.txt"), "outside");
        try { Files.createSymbolicLink(root.resolve(".gitignore"), secret); }
        catch (UnsupportedOperationException | IOException unavailable) { assumeTrue(false, "File symbolic links need Developer Mode or elevation on Windows"); }
        var workspace = workspace(); workspace.confirm(root.toString());
        assertThrows(IOException.class, workspace::proposeGitIgnore);
    }

    @Test void refusesToChooseAmongUnexpectedWorkflowDocuments() throws Exception {
        Path root = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        var workspace = workspace(); workspace.confirm(root.toString());
        var saved = workspace.saveDraft(new ProjectWorkspace.SaveDraft("", "x", "x", "1", "demo", "src/main/resources/workflows", 0, "", "{}"));
        Path extra = root.resolve(".jworkflow/copy.jworkflow.json"); Files.writeString(extra, "{}");
        var failure = assertThrows(ProjectWorkspace.Conflict.class, workspace::loadDraft);
        assertTrue(failure.getMessage().contains("copy.jworkflow.json"), failure.getMessage());
        assertThrows(ProjectWorkspace.Conflict.class, () -> workspace.saveDraft(new ProjectWorkspace.SaveDraft(saved.documentId(), "y", "y", "2", "demo", "src/main/resources/workflows", saved.revision(), saved.hash(), "{}")));
        assertEquals("{}", Files.readString(extra)); assertEquals(saved.hash(), ProjectWorkspace.sha(Files.readAllBytes(root.resolve(".jworkflow/workflow.jworkflow.json"))));
        Files.delete(extra); assertEquals(saved.revision(), workspace.loadDraft().revision());
    }

    @Test void gitignoreAdditionKeepsTheFilesLineEndings() throws Exception {
        for (String ending : new String[]{"\n", "\r\n"}) {
            Path root = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
            Files.writeString(root.resolve(".gitignore"), "target/" + ending + "build/");
            var workspace = workspace(); workspace.confirm(root.toString());
            assertEquals("target/" + ending + "build/" + ending + ".jworkflow/" + ending, workspace.proposeGitIgnore().after());
        }
        Path fresh = project("pom.xml", "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        var workspace = workspace(); workspace.confirm(fresh.toString());
        assertEquals(".jworkflow/\n", workspace.proposeGitIgnore().after());
    }

    /** Directory link: a symbolic link, or on Windows without link privilege a junction, which Java also follows. */
    private static Path link(Path link, Path target) throws Exception {
        try { return Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Symbolic links unavailable: " + unavailable.getMessage());
            Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            assumeTrue(junction.waitFor() == 0 && Files.isDirectory(link), "Could not create a junction");
            return link;
        }
    }
    private ProjectWorkspace workspace() { return new ProjectWorkspace(new LastProjectPreference(temp.resolve("preference/last.txt"))); }
    private Path project(String build, String content) throws Exception { Path root = Files.createTempDirectory(temp, "project-"); Files.writeString(root.resolve(build), content); return root; }
    private static void source(Path root, String relative, String content) throws Exception { Path file = root.resolve("src/main/java").resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, content); }
}
