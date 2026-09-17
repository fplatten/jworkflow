package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.sql.Driver;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

public record WorkflowEngineProperties(
        WorkflowEngine.Type type,
        String jdbcUrl,
        String username,
        String password,
        Driver driver,
        DataSource dataSource,
        boolean initializeSchema,
        Map<String, String> settings
) {
    public WorkflowEngineProperties {
        type = type == null ? WorkflowEngine.Type.IN_MEMORY : type;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public static WorkflowEngineProperties inMemory() {
        return new WorkflowEngineProperties(WorkflowEngine.Type.IN_MEMORY, null, null, null, null, null, false, Map.of());
    }

    public static WorkflowEngineProperties jdbc(WorkflowEngine.Type type, String jdbcUrl, String username, String password) {
        Objects.requireNonNull(type, "type");
        if (type == WorkflowEngine.Type.IN_MEMORY) {
            throw new IllegalArgumentException("Use inMemory() for in-memory workflow engines");
        }
        return new WorkflowEngineProperties(type, jdbcUrl, username, password, null, null, false, Map.of());
    }
}
