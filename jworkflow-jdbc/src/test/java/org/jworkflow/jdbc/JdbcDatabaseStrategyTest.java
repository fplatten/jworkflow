package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Metadata policy and cleanup failures that do not require Docker. */
class JdbcDatabaseStrategyTest {
    @TempDir Path directory;

    @Test void rejectsUnknownProductAndMismatchAndClosesBorrowedConnection() throws Exception {
        for (String product : new String[]{"Unknown", "SQLite"}) {
            AtomicInteger closes = new AtomicInteger();
            Connection connection = connection(product, closes, false);
            JdbcConnectionFactory factory = new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,
                    "jdbc:ignored:secret", "secret-user", "secret-password", null, source(connection), Map.of());
            SQLException failure = assertThrows(SQLException.class, factory::openPhysical);
            assertFalse(failure.toString().contains("secret"));
            assertEquals(1, closes.get());
        }
    }

    @Test void closesConnectionWhenMetadataOrSqliteConfigurationFails() {
        for (boolean metadataFailure : new boolean[]{true, false}) {
            AtomicInteger closes = new AtomicInteger();
            JdbcConnectionFactory factory = new JdbcConnectionFactory(null, null, null, null,
                    source(connection("SQLite", closes, metadataFailure)));
            assertThrows(SQLException.class, factory::openPhysical);
            assertEquals(1, closes.get());
        }
    }

    @Test void typelessPersistenceRejectsUnsupportedProductsAndClosesConnection() {
        AtomicInteger closes = new AtomicInteger();
        var unknownSource = source(connection("Unknown", closes, false));
        Map<String,String> settings = Map.of();
        WorkflowInfrastructureException failure = assertThrows(WorkflowInfrastructureException.class,
                () -> JdbcWorkflowPersistence.create(null, null, null, null, unknownSource, false, settings));
        assertTrue(failure.getCause().getMessage().contains("Unsupported JDBC database product"));
        assertEquals(1, closes.get());
    }

    @Test void resolvedBundleRejectsADataSourceThatChangesDatabaseProduct() throws Exception {
        AtomicInteger borrows = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        DataSource changing = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p,m,a) -> {
                    if (!"getConnection".equals(m.getName())) throw new UnsupportedOperationException();
                    return connection(borrows.getAndIncrement() == 0 ? "PostgreSQL" : "SQLite", closes, false);
                });
        JdbcConnectionFactory factory = new JdbcConnectionFactory(null, null, null, null, changing);
        JdbcDatabaseStrategy resolved = factory.strategy();
        assertThrows(SQLException.class, factory::openPhysical);
        assertSame(resolved, factory.strategy());
        assertEquals(2, closes.get());
    }

    @Test void rejectedAndMissingDriversDoNotExposeConnectionSecrets() {
        Driver rejecting = (Driver) Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class<?>[]{Driver.class},
                (p,m,a) -> null);
        for (Driver driver : new Driver[]{rejecting,null}) {
            JdbcConnectionFactory factory = new JdbcConnectionFactory("jdbc:unknown:secret?password=secret",null,"secret",driver,null);
            SQLException failure = assertThrows(SQLException.class,factory::openPhysical);
            assertFalse(failure.toString().contains("secret"));
            assertNull(failure.getCause(),"Do not retain a raw DriverManager URL in the cause chain");
        }
    }

    @Test void explicitSqliteKeysAreRejectedEvenAtDefaultValues() {
        for (Map<String,String> setting : java.util.List.of(Map.of("sqlite.busy-timeout-ms","5000"), Map.of("sqlite.wal-enabled","false"))) {
            AtomicInteger closes = new AtomicInteger();
            JdbcConnectionFactory factory = new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,
                    null, null, null, null, source(connection("PostgreSQL", closes, false)), setting);
            assertThrows(IllegalArgumentException.class, factory::openPhysical);
            assertEquals(1, closes.get());
        }
    }

    @Test void sqliteStillResolvesFromActualMetadataAndKeepsSettings() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("policy.db");
        JdbcConnectionFactory factory = new JdbcConnectionFactory(WorkflowEngine.Type.SQLITE, url, null, null,
                new org.sqlite.JDBC(), null, Map.of("sqlite.busy-timeout-ms","321"));
        var strategy = factory.strategy();
        assertInstanceOf(SqliteDatabaseStrategy.class, strategy);
        try (Connection connection = factory.openPhysical(); Statement statement = connection.createStatement()) {
            assertSame(strategy, factory.strategy());
            try (ResultSet row = statement.executeQuery("pragma busy_timeout")) {
                assertTrue(row.next()); assertEquals(321, row.getInt(1));
            }
            try (ResultSet row = statement.executeQuery("pragma foreign_keys")) {
                assertTrue(row.next()); assertEquals(1, row.getInt(1));
            }
        }
        var missingConnection = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL);
        assertThrows(IllegalStateException.class, missingConnection::build);
        var unsupportedPersistence = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                .jdbcUrl("jdbc:unused").persistence((org.jworkflow.persistence.WorkflowPersistence) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{org.jworkflow.persistence.WorkflowPersistence.class},
                        (p,m,a) -> null));
        assertThrows(IllegalStateException.class, unsupportedPersistence::build);
    }

    static DataSource source(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (p,m,a) -> { if ("getConnection".equals(m.getName())) return connection; throw new UnsupportedOperationException(m.getName()); });
    }

    static Connection connection(String product, AtomicInteger closes, boolean metadataFailure) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (p,m,a) -> switch (m.getName()) {
                    case "getMetaData" -> {
                        if (metadataFailure) throw new SQLException("metadata unavailable");
                        yield Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                                (mp,mm,ma) -> "getDatabaseProductName".equals(mm.getName()) ? product : null);
                    }
                    case "unwrap" -> throw new SQLException("cannot unwrap SQLite connection");
                    case "close" -> { closes.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
}
