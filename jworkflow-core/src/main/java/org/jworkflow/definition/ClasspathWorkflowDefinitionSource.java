package org.jworkflow.definition;

import org.jworkflow.engine.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Loads UTF-8 workflow definitions from explicitly named classpath resources. Resource streams are closed after
 * reading; the supplied class loader remains caller-owned.
 */
public final class ClasspathWorkflowDefinitionSource implements WorkflowDefinitionSource {
    private final ClassLoader classLoader;
    private final List<String> resourceNames;

    /**
     * Constructs ClasspathWorkflowDefinitionSource with the supplied collaborators and configuration.
     * @param resourceNames classpath resource names to load
     */
    public ClasspathWorkflowDefinitionSource(List<String> resourceNames) {
        this(Thread.currentThread().getContextClassLoader(), resourceNames);
    }

    /**
     * Constructs ClasspathWorkflowDefinitionSource with the supplied collaborators and configuration.
     * @param classLoader caller-owned class loader used to open resources
     * @param resourceNames classpath resource names to load
     * @throws NullPointerException if resourceNames is null
     */
    public ClasspathWorkflowDefinitionSource(ClassLoader classLoader, List<String> resourceNames) {
        this.classLoader = classLoader == null ? ClasspathWorkflowDefinitionSource.class.getClassLoader() : classLoader;
        this.resourceNames = List.copyOf(Objects.requireNonNull(resourceNames, "resourceNames"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkflowDefinitionText> load() {
        return resourceNames.stream().map(this::read).toList();
    }

    private WorkflowDefinitionText read(String resourceName) {
        try (InputStream inputStream = classLoader.getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new WorkflowDefinitionNotFoundException(resourceName, "classpath");
            }
            return new WorkflowDefinitionText(resourceName, new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new WorkflowInfrastructureException("Failed to read workflow definition " + resourceName, exception);
        }
    }
}
