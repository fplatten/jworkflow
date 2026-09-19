package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.model.*;
import java.util.Map;

/** Plain Java entry point: deliberately has no JUnit or driver-specific linkage. */
public final class IsolatedConsumer {
    public static void main(String[] args) throws Exception {
        absent("org.sqlite.JDBC");
        absent("org.flywaydb.core.Flyway");
        absent("liquibase.Liquibase");
        absent("org.testcontainers.postgresql.PostgreSQLContainer");
        if ("core".equals(args[0])) {
            absent("org.jworkflow.jdbc.JdbcWorkflowEngine");
            absent("org.postgresql.Driver");
            try (WorkflowEngine engine = WorkflowEngine.builder()
                    .definition(WorkflowDefinition.of("isolated","1","done",WorkflowNode.end("done"))).build()) {
                engine.start("isolated", "one", Map.of());
            }
        } else if ("missing".equals(args[0])) {
            absent("org.postgresql.Driver");
            for (WorkflowEngine.Type type : new WorkflowEngine.Type[]{WorkflowEngine.Type.POSTGRESQL,WorkflowEngine.Type.SQLITE}) {
                try {
                    WorkflowEngine.builder().type(type).jdbcUrl("jdbc:secret:password=secret").build();
                    throw new AssertionError("Missing driver should fail");
                } catch (ClassNotFoundException expected) {
                    if (!expected.getMessage().contains(type == WorkflowEngine.Type.POSTGRESQL ?
                            "org.postgresql:postgresql" : "org.xerial:sqlite-jdbc")) throw expected;
                    if (expected.toString().contains("secret")) throw new AssertionError("Secret in diagnostics");
                }
            }
        } else {
            String url = System.getenv("JWORKFLOW_TEST_URL");
            String user = System.getenv("JWORKFLOW_TEST_USER");
            String password = System.getenv("JWORKFLOW_TEST_PASSWORD");
            try (WorkflowEngine engine = WorkflowEngine.create(new WorkflowEngineProperties(
                    WorkflowEngine.Type.POSTGRESQL,url,user,password,null,null,false,
                    Map.of("recovery.timer-poll-enabled","false")))) {
                if (((JdbcWorkflowEngine)engine).type() != WorkflowEngine.Type.POSTGRESQL) throw new AssertionError("Wrong backend");
            }
            JdbcWorkflowPersistence persistence = JdbcWorkflowPersistence.create(url,user,password,null,null,false,Map.of());
            if (!persistence.definitions().findAll().isEmpty()) throw new AssertionError("Expected empty fixture");
        }
        System.out.println("ISOLATED_CONSUMER_OK " + args[0]);
    }

    private static void absent(String className) throws Exception {
        try { Class.forName(className); throw new AssertionError("Unexpected runtime dependency: " + className); }
        catch (ClassNotFoundException expected) { /* Explicitly absent from the consumer classpath. */ }
    }
}
