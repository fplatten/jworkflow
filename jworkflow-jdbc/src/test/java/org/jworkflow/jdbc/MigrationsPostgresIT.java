package org.jworkflow.jdbc;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.flywaydb.core.Flyway;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowNode;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class MigrationsPostgresIT {
    private static PostgresTestDatabase database;
    @BeforeAll static void start() { database = PostgresTestDatabase.start(); }
    @AfterAll static void stop() throws Exception { if (database != null) database.close(); }

    @Test void allMigrationOwnersProduceIdenticalApplicationCatalogsAndRerun() throws Exception {
        try (var builtin = database.createSchema(); var flyway = database.createSchema(); var liquibase = database.createSchema()) {
            initialize(builtin);
            initialize(builtin);
            Flyway tool = flyway(flyway);
            assertEquals(2, tool.migrate().migrationsExecuted);
            assertEquals(0, tool.migrate().migrationsExecuted);
            tool.validate();
            migrateLiquibase(liquibase);
            migrateLiquibase(liquibase);
            Set<String> expected = catalog(builtin);
            assertFalse(expected.isEmpty());
            assertEquals(expected, catalog(flyway));
            assertEquals(expected, catalog(liquibase));
            for (var schema : List.of(builtin, flyway, liquibase)) {
                try (Connection connection = schema.openConnection()) {
                    assertEquals(13, scalar(connection, "select count(*) from information_schema.tables where table_schema=current_schema() and (table_name like 'workflow_%' or table_name='event_status')"));
                    assertEquals(28, scalar(connection, "select count(*) from information_schema.columns where table_schema=current_schema() and (table_name like 'workflow_%' or table_name='event_status') and data_type='numeric' and numeric_precision=30 and numeric_scale=9"));
                    assertEquals(3, scalar(connection, "select count(*) from information_schema.columns where table_schema=current_schema() and column_name='claim_token' and is_nullable='YES'"));
                    assertEquals(3, scalar(connection, "select count(*) from information_schema.columns where table_schema=current_schema() and data_type='bigint'"));
                    assertEquals(3, scalar(connection, "select count(*) from information_schema.columns where table_schema=current_schema() and data_type='bytea'"));
                    assertEquals(0, scalar(connection, "select count(*) from information_schema.columns where table_schema=current_schema() and (table_name like 'workflow_%' or table_name='event_status') and data_type='character varying' and collation_name is distinct from 'C'"));
                    assertConstraintSemantics(connection);
                }
            }
            try (Connection connection = builtin.openConnection()) {
                assertEquals(2, scalar(connection, "select count(*) from jworkflow_schema_history"));
                assertEquals(1, scalar(connection, "select count(*) from information_schema.columns where table_schema=current_schema() and table_name='jworkflow_schema_history' and column_name='installed_at' and data_type='numeric' and numeric_scale=9"));
            }
            assertThrows(WorkflowInfrastructureException.class, () -> initialize(flyway));
            assertThrows(WorkflowInfrastructureException.class, () -> initialize(liquibase));
            try (Connection connection = flyway.openConnection()) {
                assertEquals(0, scalar(connection, "select count(*) from information_schema.tables where table_schema=current_schema() and table_name='jworkflow_schema_history'"));
            }
        }
    }

    @Test void concurrentEngineStartupAppliesBaselineOnce() throws Exception {
        try (var schema = database.createSchema()) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            try {
                Callable<Void> startup = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try (WorkflowEngine engine = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL)
                            .dataSource(schema.dataSource()).initialize(true).timerPolling(false).build()) {
                        assertNotNull(engine);
                    }
                    return null;
                };
                Future<Void> first = workers.submit(startup), second = workers.submit(startup);
                first.get(30, TimeUnit.SECONDS); second.get(30, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
            try (Connection connection = schema.openConnection()) {
                assertEquals(2, scalar(connection, "select count(*) from jworkflow_schema_history"));
            }
        }
    }

    @Test void migrationLockIsSchemaScopedAndReleasedAtCommit() throws Exception {
        try (var blocked = database.createSchema(); var other = database.createSchema(); Connection holder = blocked.openConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select pg_advisory_xact_lock(?,hashtext(?))")) {
                lock.setInt(1, PostgresqlSchemaInitializer.LOCK_NAMESPACE); lock.setString(2, blocked.name()); lock.execute();
            }
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> waiting = worker.submit(() -> initialize(blocked));
                // Observe an actual blocked backend, not an assumption based on elapsed time.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                boolean observed = false;
                try (Connection observer = database.openAdminConnection()) {
                    while (System.nanoTime() < deadline) {
                        if (scalar(observer, "select count(*) from pg_locks where locktype='advisory' and not granted and classid=" + PostgresqlSchemaInitializer.LOCK_NAMESPACE) > 0) { observed = true; break; }
                        Thread.sleep(10);
                    }
                }
                assertTrue(observed, "initializer must wait on the schema lock");
                assertFalse(waiting.isDone());
                initialize(other); // Independent schema progresses while the first is locked.
                holder.commit();
                waiting.get(20, TimeUnit.SECONDS);
                initialize(blocked); // No leaked session lock.
            } finally { holder.rollback(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void populatedBaselineUpgradePreservesHistoryAndData() throws Exception {
        try(var schema=database.createSchema()) {
            var first=PostgresqlSchemaInitializer.MIGRATIONS.subList(0,1);
            PostgresqlSchemaInitializer.initialize(factory(schema),first);
            try(var c=schema.openConnection();var statement=c.createStatement()) {
                statement.execute("insert into workflow_lock values ('retained','owner',1800000000.123456789)");
            }
            initialize(schema); initialize(schema);
            try(var c=schema.openConnection();var statement=c.createStatement()) {
                assertEquals(2,scalar(c,"select count(*) from jworkflow_schema_history"));
                assertEquals(1,scalar(c,"select count(*) from workflow_lock where lock_key='retained' and expires_at=1800000000.123456789"));
                assertEquals(1,scalar(c,"select count(*) from pg_indexes where schemaname=current_schema() and indexname='idx_workflow_instance_active_keyset'"));
                try(var rows=statement.executeQuery("select checksum from jworkflow_schema_history where version=1")) {
                    assertTrue(rows.next());assertEquals("2bacf955e89bc45e41b89c2ff7f608e8142173f4ef02abd3763f87cd1aca0f53",rows.getString(1));
                }
            }
        }
    }

    @Test void failedDdlRollsBackBaselineAndHistoryAndCanRetry() throws Exception {
        try (var schema = database.createSchema()) {
            List<PostgresqlSchemaInitializer.Migration> migrations = new ArrayList<>(PostgresqlSchemaInitializer.MIGRATIONS);
            migrations.add(new PostgresqlSchemaInitializer.Migration(3, "injected failure", "postgres-fixture/failing-migration.sql"));
            assertThrows(WorkflowInfrastructureException.class, () -> PostgresqlSchemaInitializer.initialize(factory(schema), migrations));
            try (Connection connection = schema.openConnection()) {
                assertEquals(0, scalar(connection, "select count(*) from information_schema.tables where table_schema=current_schema()"));
            }
            initialize(schema);
            try (Connection connection = schema.openConnection()) { assertEquals(2, scalar(connection, "select count(*) from jworkflow_schema_history")); }
        }
    }

    @Test void checksumMismatchAndUnknownVersionsFailWithoutAdoptingHistory() throws Exception {
        try (var schema = database.createSchema()) {
            initialize(schema);
            Set<String> before = catalog(schema);
            try (Connection connection = schema.openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("update jworkflow_schema_history set checksum='changed'");
            }
            WorkflowInfrastructureException failure = assertThrows(WorkflowInfrastructureException.class, () -> initialize(schema));
            assertTrue(failure.getCause().getMessage().contains("checksum differs"));
            assertEquals(before, catalog(schema));
            try (Connection connection = schema.openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("update jworkflow_schema_history set version=99 where version=1");
            }
            assertTrue(assertThrows(WorkflowInfrastructureException.class, () -> initialize(schema)).getCause().getMessage().contains("unsupported"));
        }
    }

    @Test void externalMigrationAllowsRuntimeRoleWithoutDdlOrHistoryAccess() throws Exception {
        String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        try (Connection admin = database.openAdminConnection(); Statement adminSql = admin.createStatement()) {
            // Identifiers/passwords below are generated by this test, never host input.
            adminSql.execute("create role " + role + " login password '" + password + "'");
            try (var schema = database.createSchema()) {
                flyway(schema).migrate();
                adminSql.execute("grant usage on schema " + schema.name() + " to " + role);
                try (Connection owner = schema.openConnection(); Statement grant = owner.createStatement();
                     ResultSet tables = grant.executeQuery("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'workflow_%' or tablename='event_status')")) {
                    List<String> names = new ArrayList<>(); while (tables.next()) names.add(tables.getString(1));
                    for (String name : names) adminSql.execute("grant select,insert,update,delete on " + schema.name() + "." + name + " to " + role);
                }
                PGSimpleDataSource runtime = new PGSimpleDataSource();
                runtime.setURL(((PGSimpleDataSource)schema.dataSource()).getURL());
                runtime.setUser(role); runtime.setPassword(password);
                WorkflowDefinition definition = WorkflowDefinition.of("restricted", "1", "done", WorkflowNode.end("done"));
                try (WorkflowEngine engine = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(runtime)
                        .initialize(false).timerPolling(false).definition(definition).build()) { assertNotNull(engine); }
                var persistence = JdbcWorkflowPersistence.create(null,null,null,null,runtime,false,Map.of());
                assertEquals(definition, persistence.definitions().findRevision("restricted","1",definition.revision()).orElseThrow());
                try (Connection connection = runtime.getConnection(); Statement statement = connection.createStatement()) {
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.execute("create table forbidden (id integer)")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeQuery("select * from flyway_schema_history")).getSQLState());
                }
            } finally { adminSql.execute("drop owned by " + role); adminSql.execute("drop role " + role); }
        }
    }

    @Test void unownedSchemaAndMissingSchemaAreNotSilentlyAdopted() throws Exception {
        try (var schema = database.createSchema(); Connection connection = schema.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table workflow_instance (id integer)");
            assertThrows(WorkflowInfrastructureException.class, () -> initialize(schema));
            PGSimpleDataSource missing = new PGSimpleDataSource();
            PGSimpleDataSource source = (PGSimpleDataSource)schema.dataSource();
            missing.setURL(source.getURL()); missing.setUser(source.getUser()); missing.setPassword(source.getPassword());
            missing.setCurrentSchema("absent_" + UUID.randomUUID().toString().replace("-", ""));
            assertThrows(WorkflowInfrastructureException.class, () -> JdbcWorkflowPersistence.create(null,null,null,null,missing,true,Map.of()));
        }
    }

    @Test void migrationBorrowRestoresHostSettingsAndNeverCommitsHostWork() throws Exception {
        try (var schema=database.createSchema(); Connection physical=schema.openConnection()) {
            try(Statement statement=physical.createStatement()) { statement.execute("set search_path to "+schema.name()+", public"); }
            String path;
            try(Statement statement=physical.createStatement();ResultSet rows=statement.executeQuery("show search_path")) { rows.next();path=rows.getString(1); }
            physical.setReadOnly(true); physical.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            java.util.concurrent.atomic.AtomicInteger returns=new java.util.concurrent.atomic.AtomicInteger();
            Connection borrow=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("close")){returns.incrementAndGet();return null;}
                try{return m.invoke(physical,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            JdbcConnectionFactory factory=new JdbcConnectionFactory(null,null,null,null,JdbcDatabaseStrategyTest.source(borrow));
            JdbcSchemaInitializer.initialize(factory);
            List<PostgresqlSchemaInitializer.Migration> failing=new ArrayList<>(PostgresqlSchemaInitializer.MIGRATIONS);
            failing.add(new PostgresqlSchemaInitializer.Migration(3,"failure","postgres-fixture/failing-migration.sql"));
            assertThrows(WorkflowInfrastructureException.class,()->PostgresqlSchemaInitializer.initialize(factory,failing));
            assertTrue(physical.getAutoCommit()); assertTrue(physical.isReadOnly());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE,physical.getTransactionIsolation());
            try(Statement statement=physical.createStatement();ResultSet rows=statement.executeQuery("show search_path")) { rows.next();assertEquals(path,rows.getString(1)); }
            assertEquals(3,returns.get(),"metadata validation and both migration borrows are returned");
            physical.setReadOnly(false);physical.setAutoCommit(false);
            try(Statement statement=physical.createStatement()) { statement.executeUpdate("insert into workflow_lock values ('host-work','host',0)"); }
            assertThrows(WorkflowInfrastructureException.class,()->JdbcSchemaInitializer.initialize(factory));
            assertFalse(physical.getAutoCommit());
            try(Connection observer=schema.openConnection()) { assertEquals(0,scalar(observer,"select count(*) from workflow_lock")); }
            physical.rollback();physical.setAutoCommit(true);
            assertEquals(0,scalar(physical,"select count(*) from workflow_lock"));
        }
    }

    static JdbcConnectionFactory factory(PostgresTestDatabase.Schema schema) {
        return new JdbcConnectionFactory(null,null,null,null,schema.dataSource());
    }
    static void initialize(PostgresTestDatabase.Schema schema) { JdbcSchemaInitializer.initialize(factory(schema)); }
    private static Flyway flyway(PostgresTestDatabase.Schema schema) {
        return Flyway.configure().dataSource(schema.dataSource()).defaultSchema(schema.name()).schemas(schema.name())
                .createSchemas(false).locations("classpath:db/postgresql/migration").load();
    }
    private static void migrateLiquibase(PostgresTestDatabase.Schema schema) throws Exception {
        try (Connection connection = schema.openConnection()) {
            var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            db.setDefaultSchemaName(schema.name()); db.setLiquibaseSchemaName(schema.name());
            try (Liquibase tool = new Liquibase("db/postgresql/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(), db)) { tool.update(new Contexts()); }
        }
    }
    private static Set<String> catalog(PostgresTestDatabase.Schema schema) throws Exception {
        TreeSet<String> result = new TreeSet<>();
        try (Connection connection = schema.openConnection()) {
            for (String query : List.of(
                    "select 'column',c.relname,a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_get_expr(d.adbin,d.adrelid),co.collname from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_attribute a on a.attrelid=c.oid left join pg_attrdef d on d.adrelid=c.oid and d.adnum=a.attnum left join pg_collation co on co.oid=a.attcollation where n.nspname=current_schema() and c.relkind='r' and (c.relname like 'workflow_%' or c.relname='event_status') and a.attnum>0 and not a.attisdropped",
                    "select 'constraint',c.relname,x.conname,pg_get_constraintdef(x.oid) from pg_constraint x join pg_class c on c.oid=x.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and (c.relname like 'workflow_%' or c.relname='event_status')",
                    "select 'index',tablename,indexname,indexdef from pg_indexes where schemaname=current_schema() and (tablename like 'workflow_%' or tablename='event_status')")) {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
                    while (rows.next()) {
                        List<String> values = new ArrayList<>();
                        for (int i=1;i<=rows.getMetaData().getColumnCount();i++) values.add(String.valueOf(rows.getString(i)).replace(schema.name(), "<schema>"));
                        result.add(String.join("|",values));
                    }
                }
            }
        }
        return result;
    }
    private static void assertConstraintSemantics(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("insert into workflow_inbox (id,external_event_id,source_system,received_at,status_value) values ('one','external','source',0,'RECEIVED')");
            assertEquals("23505", assertThrows(SQLException.class, () -> statement.execute("insert into workflow_inbox (id,external_event_id,source_system,received_at,status_value) values ('two','external','source',0,'RECEIVED')")).getSQLState());
            assertEquals("23503", assertThrows(SQLException.class, () -> statement.execute("insert into workflow_inbox_attempt values ('attempt','missing',1,'FAILED',null,null,0)")).getSQLState());
            assertEquals("23502", assertThrows(SQLException.class, () -> statement.execute("insert into workflow_inbox (id,external_event_id,source_system,received_at,status_value) values ('null-time','new','source',null,'RECEIVED')")).getSQLState());
            assertEquals("22001", assertThrows(SQLException.class, () -> statement.execute("insert into workflow_lock values (repeat('x',256),'owner',0)")).getSQLState());
            assertEquals(1, scalar(connection, "select count(*) from workflow_inbox where attempt_count=0 and message_redaction_status='VISIBLE' and claim_token is null"));
        }
    }
    static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
}
