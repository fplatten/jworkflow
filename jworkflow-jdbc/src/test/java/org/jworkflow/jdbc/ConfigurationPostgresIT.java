package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.model.*;
import org.jworkflow.events.NoOpEventPublisher;
import org.jworkflow.observability.NoOpWorkflowLifecycleObserver;
import org.jworkflow.security.CaptureAllEventPolicy;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real connection/configuration tests using the PostgreSQL application baseline. */
class ConfigurationPostgresIT {
    private static PostgresTestDatabase database;
    private static final Map<String,String> SETTINGS = Map.of("recovery.timer-poll-enabled","false");

    @BeforeAll static void start() { database = PostgresTestDatabase.start(); }
    @AfterAll static void stop() throws Exception { if (database != null) database.close(); }

    @Test void constructsThroughUrlDriverDataSourceAndProperties() throws Exception {
        try (var schema = startupSchema()) {
            PGSimpleDataSource source = (PGSimpleDataSource) schema.dataSource();
            try (WorkflowEngine engine = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                    .jdbcUrl(source.getURL()).username(source.getUser()).password(source.getPassword())
                    .timerPolling(false).build()) { assertEquals(WorkflowEngine.Type.POSTGRESQL, ((JdbcWorkflowEngine)engine).type()); }
            Driver driver = new org.postgresql.Driver();
            try (WorkflowEngine engine = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                    .jdbcUrl(source.getURL()).username(source.getUser()).password(source.getPassword())
                    .driver(driver).timerPolling(false).build()) {
                assertSame(driver, ((JdbcWorkflowEngine)engine).connectionFactory().driver());
            }
            AtomicInteger closes = new AtomicInteger();
            DataSource host = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DataSource.class, AutoCloseable.class}, (p,m,a) -> {
                        if ("close".equals(m.getName())) { closes.incrementAndGet(); return null; }
                        try { return m.invoke(source,a); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
            // DataSource owns credentials and product; the other fields must be ignored.
            try (WorkflowEngine engine = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                    .jdbcUrl("jdbc:sqlite:ignored-secret").username("ignored").password("ignored")
                    .driver(new org.sqlite.JDBC()).dataSource(host).timerPolling(false).build()) {
                assertInstanceOf(PostgresqlDatabaseStrategy.class, ((JdbcWorkflowEngine)engine).connectionFactory().strategy());
            }
            assertEquals(0, closes.get());
            try (Connection connection = source.getConnection()) { assertFalse(connection.isClosed()); }
            Properties properties = new Properties();
            properties.setProperty("jworkflow.engine.type","postgresql");
            properties.setProperty("jworkflow.jdbc.url",source.getURL());
            properties.setProperty("jworkflow.jdbc.username",source.getUser());
            properties.setProperty("jworkflow.jdbc.password",source.getPassword());
            properties.setProperty("jworkflow.setting.recovery.timer-poll-enabled","false");
            try (WorkflowEngine engine = WorkflowEngine.builder().properties(properties).build()) {
                assertEquals(WorkflowEngine.Type.POSTGRESQL, ((JdbcWorkflowEngine)engine).type());
            }
            IsolatedConsumerTest.run("postgres", Map.of("JWORKFLOW_TEST_URL",source.getURL(),
                    "JWORKFLOW_TEST_USER",source.getUser(),"JWORKFLOW_TEST_PASSWORD",source.getPassword()));
        }
    }

    @Test void publicFactoryOverloadsRemainAvailable() throws Exception {
        try (var schema = startupSchema()) {
            DataSource source = schema.dataSource();
            try (var engine = JdbcWorkflowEngine.create(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,source,false)) {
                assertEquals(WorkflowEngine.Type.POSTGRESQL, ((JdbcWorkflowEngine)engine).type());
            }
            var definitions = new WorkflowDefinitionRegistry();
            var conditions = new BranchConditionEvaluator();
            try (var engine = JdbcWorkflowEngine.create(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,source,false,
                    definitions,NoOpEventPublisher.INSTANCE,Map.of(),conditions,Map.of(),SETTINGS,Map.of())) { assertNotNull(engine); }
            try (var engine = JdbcWorkflowEngine.create(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,source,false,
                    definitions,NoOpEventPublisher.INSTANCE,Map.of(),conditions,Map.of(),SETTINGS,Map.of(),Clock.systemUTC())) { assertNotNull(engine); }
            try (var engine = JdbcWorkflowEngine.create(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,source,false,
                    definitions,NoOpEventPublisher.INSTANCE,Map.of(),conditions,Map.of(),SETTINGS,Map.of(),
                    NoOpWorkflowLifecycleObserver.INSTANCE,Clock.systemUTC())) { assertNotNull(engine); }
            try (var engine = JdbcWorkflowEngine.create(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,source,false,
                    definitions,NoOpEventPublisher.INSTANCE,Map.of(),conditions,Map.of(),SETTINGS,Map.of(),
                    NoOpWorkflowLifecycleObserver.INSTANCE,Clock.systemUTC(),CaptureAllEventPolicy.INSTANCE)) { assertNotNull(engine); }
            var persistence = JdbcWorkflowPersistence.create(null,null,null,null,source,false,Map.of());
            assertTrue(persistence.definitions().findAll().isEmpty());
        }
    }

    @Test void rejectsWrongProductAndSqliteSettingsBeforeCreatingObjects() throws Exception {
        try (var schema = database.createSchema()) {
            DataSource source = schema.dataSource();
            assertThrows(WorkflowInfrastructureException.class, () -> WorkflowEngine.builder()
                    .type(WorkflowEngine.Type.SQLITE).dataSource(source).build());
            assertThrows(IllegalArgumentException.class, () -> WorkflowEngine.builder()
                    .type(WorkflowEngine.Type.POSTGRESQL).dataSource(source).sqliteWalEnabled(false).build());
            assertThrows(IllegalArgumentException.class, () -> JdbcWorkflowPersistence.create(null,null,null,null,
                    source,false,Map.of("sqlite.busy-timeout-ms","5000")));
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema=current_schema()")) {
                assertTrue(rows.next()); assertEquals(0,rows.getInt(1));
            }
        }
    }

    @Test void sharedStrategyAndConnectionStateRestoration() throws Exception {
        try (var schema = database.createSchema()) {
            List<Connection> returned = new ArrayList<>();
            DataSource original = schema.dataSource();
            DataSource host = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (p,m,a) -> {
                        if (!"getConnection".equals(m.getName())) throw new UnsupportedOperationException();
                        Connection connection = original.getConnection();
                        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                        // Closing the adapter borrow returns it to this simulated host pool.
                        returned.add(connection);
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                                (cp,cm,ca) -> {
                                    if ("close".equals(cm.getName())) return null;
                                    try { return cm.invoke(connection,ca); }
                                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                                });
                    });
            try {
                JdbcConnectionFactory factory = new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,host,Map.of());
                JdbcDatabaseStrategy strategy = factory.strategy();
                JdbcTransactionManager manager = new JdbcTransactionManager(factory);
                manager.inTransaction(() -> {
                    try (Connection first = factory.open(); Connection second = factory.open()) {
                        assertSame(first.unwrap(Connection.class),second.unwrap(Connection.class));
                        first.setReadOnly(true);
                        first.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                        manager.inTransaction(() -> { assertSame(strategy,factory.strategy()); return null; });
                    } catch (SQLException e) { throw new RuntimeException(e); }
                    return null;
                });
                IllegalStateException injected = new IllegalStateException("injected rollback");
                assertSame(injected, assertThrows(IllegalStateException.class, () -> manager.inTransaction(() -> {
                    try (Connection connection = factory.open()) {
                        connection.setReadOnly(true);
                        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    } catch (SQLException failure) { throw new RuntimeException(failure); }
                    throw injected;
                })));
                for (Connection connection : returned) {
                    assertTrue(connection.getAutoCommit());
                    assertFalse(connection.isReadOnly());
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
                }
            } finally { for (Connection connection : returned) connection.close(); }
        }
    }

    private static PostgresTestDatabase.Schema startupSchema() throws Exception {
        var schema = database.createSchema();
        JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
        return schema;
    }
}
