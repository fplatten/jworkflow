package org.jworkflow.jdbc;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.flywaydb.core.Flyway;
import org.sqlite.JDBC;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class JdbcTransactionSchemaContractTest {
    public static void main(String[] args) throws Exception {
        transactionContractUsesDataSource();
        transactionContractUsesDriver();
        rollbackLeavesNoPartialWrites();
        schemaInitializerIsVersionedAndRerunnable();
        migratesRepresentativeV1Rows();
        flywayLiquibaseAndInitializerHaveParity();
        constraintsAndIndexesAreEffective();
        sqliteSettingsApplyToDriverAndDataSource();
        sqliteImmediateTransactionsSerializeWriters();
    }

    private static void transactionContractUsesDataSource() {
        TrackingConnection tracking = new TrackingConnection();
        TrackingDataSource source = new TrackingDataSource(tracking);
        verifyTransactionContract(new JdbcConnectionFactory(null, null, null, null, source), tracking, source.opens);
    }

    private static void transactionContractUsesDriver() {
        TrackingConnection tracking = new TrackingConnection();
        TrackingDriver driver = new TrackingDriver(tracking);
        verifyTransactionContract(new JdbcConnectionFactory("jdbc:tracking:test", null, null, driver, null), tracking, driver.opens);
    }

    private static void verifyTransactionContract(JdbcConnectionFactory factory, TrackingConnection tracking, AtomicInteger opens) {
        JdbcTransactionManager manager = new JdbcTransactionManager(factory);
        String result = manager.inTransaction(() -> unchecked(() -> {
            Connection firstRepositoryConnection = factory.open();
            Connection physical = firstRepositoryConnection.unwrap(Connection.class);
            firstRepositoryConnection.close();
            manager.execute(() -> unchecked(() -> {
                try (Connection secondRepositoryConnection = factory.open()) {
                    check(secondRepositoryConnection.unwrap(Connection.class) == physical,
                            "nested repository work must receive the same physical connection");
                }
                return null;
            }));
            check(!physical.isClosed(), "repository close must not close a transaction-owned connection");
            return "committed";
        }));
        check("committed".equals(result), "result-bearing transaction must return its value");
        check(opens.get() == 1 && tracking.commits.get() == 1 && tracking.rollbacks.get() == 0,
                "outer success must acquire and commit exactly once");
        check(tracking.closes.get() == 1 && tracking.autoCommit,
                "connection settings must be restored and released after success");

        TrackingConnection failed = new TrackingConnection();
        JdbcConnectionFactory failedFactory = factory.dataSource() != null
                ? new JdbcConnectionFactory(null, null, null, null, new TrackingDataSource(failed))
                : new JdbcConnectionFactory("jdbc:tracking:failure", null, null, new TrackingDriver(failed), null);
        JdbcTransactionManager failedManager = new JdbcTransactionManager(failedFactory);
        expect(IllegalStateException.class, () -> failedManager.execute(() -> unchecked(() -> {
            try (Connection ignored = failedFactory.open()) { throw new IllegalStateException("fail"); }
        })));
        check(failed.commits.get() == 0 && failed.rollbacks.get() == 1 && failed.closes.get() == 1,
                "failure must roll back once and release the connection");
        check(failed.autoCommit, "auto-commit must be restored after failure");
    }

    private static void schemaInitializerIsVersionedAndRerunnable() throws Exception {
        Path database = Files.createTempFile("jworkflow-initializer-", ".sqlite");
        JdbcConnectionFactory factory = sqliteFactory(database, 500);
        JdbcSchemaInitializer.initialize(factory);
        JdbcSchemaInitializer.initialize(factory);
        try (Connection connection = factory.open()) {
            check(scalar(connection, "select count(*) from jworkflow_schema_history") == 6,
                    "initializer must record each migration exactly once");
        }
    }

    private static void rollbackLeavesNoPartialWrites() throws Exception {
        Path database = Files.createTempFile("jworkflow-rollback-", ".sqlite");
        JdbcConnectionFactory factory = sqliteFactory(database, 500);
        JdbcSchemaInitializer.initialize(factory);
        JdbcTransactionManager manager = new JdbcTransactionManager(factory);
        expect(IllegalStateException.class, () -> manager.execute(() -> unchecked(() -> {
            try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("insert into workflow_lock values ('rollback-1','worker','later')");
                statement.executeUpdate("insert into workflow_inbox (id,external_event_id,source_system,received_at,status_value) values ('rollback-1','event-1','erp','now','RECEIVED')");
            }
            throw new IllegalStateException("force rollback");
        })));
        try (Connection connection = factory.open()) {
            check(scalar(connection, "select count(*) from workflow_lock where lock_key='rollback-1'") == 0,
                    "rollback must remove the first write");
            check(scalar(connection, "select count(*) from workflow_inbox where id='rollback-1'") == 0,
                    "rollback must remove writes performed through another repository handle");
        }
    }

    private static void migratesRepresentativeV1Rows() throws Exception {
        Path database = Files.createTempFile("jworkflow-v1-v2-", ".sqlite");
        JdbcConnectionFactory factory = sqliteFactory(database, 500);
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
            for (String sql : JdbcSchemaInitializer.statements(
                    JdbcSchemaInitializer.read("db/migration/V1__create_jworkflow_schema.sql"))) statement.execute(sql);
            statement.executeUpdate("insert into workflow_instance (id, workflow_key, business_key, current_state, status, variables, lock_version, created_at, updated_at) values ('i1','orders','o1','waiting','WAITING','{}',0,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        }
        JdbcSchemaInitializer.initialize(factory);
        try (Connection connection = factory.open()) {
            check(scalar(connection, "select count(*) from workflow_instance where id='i1'") == 1,
                    "V1 representative rows must survive V2 migration");
            check(columns(connection).get("workflow_instance").contains("pending_wait_json"),
                    "V2 column must be present after migration");
        }
    }

    private static void flywayLiquibaseAndInitializerHaveParity() throws Exception {
        Path initializerDb = Files.createTempFile("jworkflow-init-", ".sqlite");
        Path flywayDb = Files.createTempFile("jworkflow-flyway-", ".sqlite");
        Path liquibaseDb = Files.createTempFile("jworkflow-liquibase-", ".sqlite");
        JdbcSchemaInitializer.initialize(sqliteFactory(initializerDb, 500));
        Flyway flyway = Flyway.configure().dataSource(url(flywayDb), null, null).locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.migrate();
        try (Connection connection = java.sql.DriverManager.getConnection(url(liquibaseDb));
             Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(new Contexts());
            liquibase.update(new Contexts());
        }
        Map<String, Set<String>> initializer = metadata(initializerDb);
        check(initializer.equals(metadata(flywayDb)), "Flyway schema must match initializer schema");
        check(initializer.equals(metadata(liquibaseDb)), "Liquibase schema must match initializer schema");
    }

    private static void constraintsAndIndexesAreEffective() throws Exception {
        Path database = Files.createTempFile("jworkflow-constraints-", ".sqlite");
        JdbcConnectionFactory factory = sqliteFactory(database, 500);
        JdbcSchemaInitializer.initialize(factory);
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into workflow_inbox (id,external_event_id,source_system,received_at,status_value) values ('1','e1','erp','now','RECEIVED')");
            expect(SQLException.class, () -> statement.executeUpdate("insert into workflow_inbox (id,external_event_id,source_system,received_at,status_value) values ('2','e1','erp','now','RECEIVED')"));
            Set<String> indexes = indexes(connection);
            for (String required : Set.of("idx_workflow_instance_active", "idx_workflow_timer_claimable",
                    "idx_workflow_inbox_claimable", "idx_workflow_outbox_claimable")) {
                check(indexes.contains(required), "missing required index " + required);
            }
        }
    }

    private static void sqliteImmediateTransactionsSerializeWriters() throws Exception {
        Path database = Files.createTempFile("jworkflow-claims-", ".sqlite");
        JdbcConnectionFactory firstFactory = sqliteFactory(database, 100);
        JdbcConnectionFactory secondFactory = sqliteFactory(database, 100);
        JdbcSchemaInitializer.initialize(firstFactory);
        JdbcTransactionManager first = new JdbcTransactionManager(firstFactory);
        JdbcTransactionManager second = new JdbcTransactionManager(secondFactory);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> first.inImmediateTransaction(() -> unchecked(() -> {
                try (Connection connection = firstFactory.open(); Statement statement = connection.createStatement()) {
                    statement.executeUpdate("insert into workflow_lock values ('first','worker-1','later')");
                    locked.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("writer release timed out");
                    return null;
                }
            })));
            check(locked.await(2, TimeUnit.SECONDS), "first writer must acquire its claim transaction");
            expect(RuntimeException.class, () -> second.inImmediateTransaction(() -> null));
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        second.inImmediateTransaction(() -> unchecked(() -> {
            try (Connection connection = secondFactory.open(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("insert into workflow_lock values ('second','worker-2','later')");
            }
            return null;
        }));
    }

    private static void sqliteSettingsApplyToDriverAndDataSource() throws Exception {
        Path driverDb = Files.createTempFile("jworkflow-driver-settings-", ".sqlite");
        JdbcConnectionFactory driverFactory = new JdbcConnectionFactory(url(driverDb), null, null, new JDBC(), null, 321, false);
        assertSqliteSettings(driverFactory);

        Path dataSourceDb = Files.createTempFile("jworkflow-datasource-settings-", ".sqlite");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url(dataSourceDb));
        JdbcConnectionFactory dataSourceFactory = new JdbcConnectionFactory(null, null, null, null, dataSource, 321, false);
        assertSqliteSettings(dataSourceFactory);
    }

    private static void assertSqliteSettings(JdbcConnectionFactory factory) throws SQLException {
        try (Connection connection = factory.open()) {
            check(scalar(connection, "pragma foreign_keys") == 1, "SQLite foreign keys must be enabled per connection");
            check(scalar(connection, "pragma busy_timeout") == 321, "SQLite busy timeout must be configured per connection");
        }
    }

    private static JdbcConnectionFactory sqliteFactory(Path path, int busyTimeout) throws Exception {
        return new JdbcConnectionFactory(url(path), null, null, new JDBC(), null, busyTimeout, false);
    }

    private static String url(Path path) { return "jdbc:sqlite:" + path.toAbsolutePath(); }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next(); return rows.getLong(1);
        }
    }

    private static Map<String, Set<String>> metadata(Path path) throws SQLException {
        try (Connection connection = java.sql.DriverManager.getConnection(url(path))) { return columns(connection); }
    }

    private static Map<String, Set<String>> columns(Connection connection) throws SQLException {
        Map<String, Set<String>> result = new HashMap<>();
        try (ResultSet tables = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (!(table.startsWith("workflow_") || table.equals("event_status"))) continue;
                Set<String> names = new HashSet<>();
                try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, null)) {
                    while (columns.next()) names.add(columns.getString("COLUMN_NAME"));
                }
                result.put(table, names);
            }
        }
        return result;
    }

    private static Set<String> indexes(Connection connection) throws SQLException {
        Set<String> result = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : columns(connection).keySet()) {
            try (ResultSet indexes = metadata.getIndexInfo(null, null, table, false, false)) {
                while (indexes.next()) if (indexes.getString("INDEX_NAME") != null) result.add(indexes.getString("INDEX_NAME"));
            }
        }
        return result;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable work) {
        try { work.run(); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (Throwable failure) { if (!type.isInstance(failure)) throw new RuntimeException(failure); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    private static <T> T unchecked(ThrowingSupplier<T> work) {
        try { return work.get(); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private static final class TrackingConnection {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private boolean autoCommit = true;
        private boolean readOnly;
        private boolean closed;
        private int isolation = Connection.TRANSACTION_READ_COMMITTED;
        private final Connection proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (ignored, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                            new Class<?>[]{DatabaseMetaData.class}, (p, m, a) ->
                                    "getDatabaseProductName".equals(m.getName()) ? "PostgreSQL" : defaultValue(m.getReturnType()));
                    case "getAutoCommit" -> autoCommit;
                    case "createStatement" -> TransactionTestDataSource.validationStatement();
                    case "setAutoCommit" -> { autoCommit = (boolean) args[0]; yield null; }
                    case "isReadOnly" -> readOnly;
                    case "setReadOnly" -> { readOnly = (boolean) args[0]; yield null; }
                    case "getTransactionIsolation" -> isolation;
                    case "setTransactionIsolation" -> { isolation = (int) args[0]; yield null; }
                    case "commit" -> { commits.incrementAndGet(); yield null; }
                    case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                    case "close" -> { closed = true; closes.incrementAndGet(); yield null; }
                    case "isClosed" -> closed;
                    case "unwrap" -> ((Class<?>) args[0]).isInstance(ignored) ? ignored : null;
                    case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(ignored);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class TrackingDataSource implements DataSource {
        private final TrackingConnection connection; private final AtomicInteger opens = new AtomicInteger();
        private TrackingDataSource(TrackingConnection connection) { this.connection = connection; }
        public Connection getConnection() { opens.incrementAndGet(); return connection.proxy; }
        public Connection getConnection(String u, String p) { return getConnection(); }
        public PrintWriter getLogWriter() { return null; } public void setLogWriter(PrintWriter out) { }
        public void setLoginTimeout(int seconds) { } public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        public <T> T unwrap(Class<T> iface) { return null; } public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static final class TrackingDriver implements Driver {
        private final TrackingConnection connection; private final AtomicInteger opens = new AtomicInteger();
        private TrackingDriver(TrackingConnection connection) { this.connection = connection; }
        public Connection connect(String url, Properties info) { opens.incrementAndGet(); return connection.proxy; }
        public boolean acceptsURL(String url) { return true; }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; } public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L;
        return null;
    }
    @org.junit.jupiter.api.Test
    void junitContract() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> main(new String[0]));
    }
}
