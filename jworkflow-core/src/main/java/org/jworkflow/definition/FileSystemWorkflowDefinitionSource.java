package org.jworkflow.definition;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class FileSystemWorkflowDefinitionSource implements WorkflowDefinitionSource {
    private final Path root;

    public FileSystemWorkflowDefinitionSource(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

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
