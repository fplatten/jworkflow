package org.jworkflow.example;

import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.engine.WorkflowEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class PostgresqlOrderExampleTest {
    @TempDir Path directory;

    @Test void phasesSurviveRestartAndRepeatedDelivery() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("example.sqlite");
        PostgresqlOrderExample.run("init", builder(url));
        assertEquals(0, count(url, "workflow_instance"));
        PostgresqlOrderExample.run("start", builder(url));
        PostgresqlOrderExample.run("start", builder(url));
        assertEquals(1, count(url, "workflow_instance"));
        assertEquals(1, count(url, "workflow_inbox"));
        PostgresqlOrderExample.run("resume", builder(url));
        PostgresqlOrderExample.run("resume", builder(url));
        PostgresqlOrderExample.run("status", builder(url));
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select status from workflow_instance")) {
            assertTrue(rows.next());
            assertEquals("COMPLETED", rows.getString(1));
            assertFalse(rows.next());
        }
        assertEquals(2, count(url, "workflow_command_result"));
        PostgresqlOrderExample.run("publish", builder(url));
        PostgresqlOrderExample.run("publish", builder(url));
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from workflow_outbox where status_value <> 'PUBLISHED'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        assertTrue(count(url, "workflow_outbox") > 0);
    }

    @Test void invalidCommandLineFailsBeforeOpeningDatabase() {
        String[] missing = {};
        String[] unknown = {"unknown"};
        String[] extra = {"start", "status"};
        assertThrows(IllegalArgumentException.class, () -> PostgresqlOrderExample.main(missing));
        assertThrows(IllegalArgumentException.class, () -> PostgresqlOrderExample.main(unknown));
        assertThrows(IllegalArgumentException.class, () -> PostgresqlOrderExample.main(extra));
    }

    private static WorkflowEngineBuilder builder(String url) {
        return WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(url);
    }

    private static int count(String url, String table) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
