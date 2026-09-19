package org.jworkflow.engine;


import java.sql.Driver;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Backend and connection configuration plus adapter settings. A null type selects in-memory mode and null settings
 *  become an empty immutable map. DataSource configuration takes precedence over Driver, URL and credentials.
 *  Credentials should come from host configuration and must not be logged.
 * @param type selected engine backend
 * @param jdbcUrl JDBC connection URL; supply credentials separately and select a trusted schema
 * @param username host-provided database username
 * @param password host-provided database password; do not log this value
 * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
 * @param dataSource host-owned source of idle JDBC connections; the adapter does not close the source
 * @param initializeSchema whether this adapter owns built-in schema migration; false for external migration owners
 * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
 */
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
    /**
     * Creates this value from the supplied components.
     * @param type selected engine backend
     * @param jdbcUrl JDBC connection URL; supply credentials separately and select a trusted schema
     * @param username host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param dataSource host-owned source of idle JDBC connections; the adapter does not close the source
     * @param initializeSchema whether this adapter owns built-in schema migration; false for external migration
     *      owners
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     */
    public WorkflowEngineProperties {
        type = type == null ? WorkflowEngine.Type.IN_MEMORY : type;
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /**
     * Creates default in-memory properties with no JDBC configuration.
     * @return the resulting workflow engine properties
     */
    public static WorkflowEngineProperties inMemory() {
        return new WorkflowEngineProperties(WorkflowEngine.Type.IN_MEMORY, null, null, null, null, null, false, Map.of());
    }

    /**
     * Creates URL/credential properties for a durable backend, with initialization disabled. IN_MEMORY is rejected
     *  by this factory.
     * @param type selected engine backend
     * @param jdbcUrl JDBC connection URL; supply credentials separately and select a trusted schema
     * @param username host-provided database username
     * @param password host-provided database password; do not log this value
     * @return the resulting workflow engine properties
     * @throws NullPointerException if type is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public static WorkflowEngineProperties jdbc(WorkflowEngine.Type type, String jdbcUrl, String username, String password) {
        Objects.requireNonNull(type, "type");
        if (type == WorkflowEngine.Type.IN_MEMORY) {
            throw new IllegalArgumentException("Use inMemory() for in-memory workflow engines");
        }
        return new WorkflowEngineProperties(type, jdbcUrl, username, password, null, null, false, Map.of());
    }
}
