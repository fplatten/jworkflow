package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Stores only a suggested path; reading the preference never opens the suggested project. */
public final class LastProjectPreference {
    private final Path file;

    public LastProjectPreference(Path file) { this.file = file; }

    public String read() throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "";
        if (Files.size(file) > 4096) return "";
        return Files.readString(file, StandardCharsets.UTF_8).strip();
    }

    /** Invoke only after explicit root confirmation; M1 does not expose a root-changing endpoint. */
    public void remember(Path confirmedRoot) throws IOException {
        String value = confirmedRoot.toAbsolutePath().normalize().toString();
        if (value.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("Project path too long");
        Files.createDirectories(file.getParent());
        if (Files.isSymbolicLink(file)) throw new IOException("Preference must not be a symbolic link");
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    public static Path defaultLocation(String os, String home, String localAppData) {
        Path base = os.startsWith("Windows") && localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData) : Path.of(home, "Library", "Application Support");
        return base.resolve("JWorkflow Workbench").resolve("last-project.txt");
    }
}
