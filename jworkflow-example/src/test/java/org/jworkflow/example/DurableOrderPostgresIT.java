package org.jworkflow.example;

import org.jworkflow.engine.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.Duration;
import java.util.UUID;

class DurableOrderPostgresIT {
    @Test @Timeout(120) void orderRestartsThroughInboxAndOutbox() throws Exception {
        // An owned database/container: never touches a host database and never skips on startup failure.
        try(var database=new PostgreSQLContainer(System.getProperty("postgres.image"))
                .withDatabaseName("order_example").withUsername("order_example")
                .withPassword(UUID.randomUUID().toString()).withCommand("postgres","-c","fsync=on")
                .withStartupTimeout(Duration.ofSeconds(60)).withReuse(false)) {
            database.start();
            var source=new PGSimpleDataSource();source.setURL(database.getJdbcUrl());
            source.setUser(database.getUsername());source.setPassword(database.getPassword());
            source.setConnectTimeout(10);source.setSocketTimeout(30);
            source.setOptions("-c statement_timeout=10000 -c lock_timeout=5000");
            try(var connection=source.getConnection()){
                System.out.println("Order restart: PostgreSQL "+connection.getMetaData().getDatabaseProductVersion()
                        +", driver "+connection.getMetaData().getDriverVersion()+", Java "+System.getProperty("java.version")+", image "+database.getDockerImageName());
            }
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> DurableOrderInboxRestartScenario.run(()->WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                    .dataSource(source).initialize(true).timerPolling(false)));
        }
    }
}
