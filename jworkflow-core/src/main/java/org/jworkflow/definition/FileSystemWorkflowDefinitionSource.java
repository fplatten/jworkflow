package org.jworkflow.definition;

import org.jworkflow.engine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads Groovy workflow sources from a configured filesystem root. Loading is an explicit operation, not a
 * directory-watching service.
 */
public final class FileSystemWorkflowDefinitionSource implements WorkflowDefinitionSource {
    private final Path root;

    /**
     * Constructs FileSystemWorkflowDefinitionSource with the supplied collaborators and configuration.
     * @param root filesystem root from which Groovy sources are discovered
     * @throws NullPointerException if root is null
     */
    public FileSystemWorkflowDefinitionSource(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkflowDefinitionText> load() {
        if (!Files.exists(root)) {
            throw new WorkflowDefinitionNotFoundException(root.toString(), "filesystem");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".groovy"))
                    .sorted()
                    .map(this::read)
                    .toList();
        } catch (IOException exception) {
            throw new WorkflowInfrastructureException("Failed to load workflow definitions from " + root, exception);
        }
    }

    private WorkflowDefinitionText read(Path path) {
        try {
            return new WorkflowDefinitionText(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new WorkflowInfrastructureException("Failed to read workflow definition " + path, exception);
        }
    }
}
