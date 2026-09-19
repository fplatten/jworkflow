package org.jworkflow.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.events.EventName;
import org.jworkflow.model.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Reproducible operational ceilings, not a throughput benchmark. */
@Timeout(180)
class OperationsPostgresIT {
    private static PostgresTestDatabase database;
    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L, 123_456_789);
    private static final String EPOCH = "1800000000.123456789";
    private static final Path RESULTS = Path.of("target", "pg12-evidence");
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void start() throws Exception {
        Files.createDirectories(RESULTS);
        database = PostgresTestDatabase.start(true);
        try (var c = database.openAdminConnection()) {
            record("environment", "server=" + scalar(c,"show server_version") + " driver=" + c.getMetaData().getDriverVersion()
                    + " java=" + System.getProperty("java.version") + " image=" + database.image()
                    + " imageId=" + database.imageId() + " cpus=2 memoryBytes=1073741824 fsync=" + scalar(c,"show fsync")
                    + " shared_buffers=" + scalar(c,"show shared_buffers") + " work_mem=" + scalar(c,"show work_mem"));
        }
    }
    @AfterAll static void stop() throws Exception { if (database != null) database.close(); }

    @Test void representativeBacklogsPlansStartupAndConcurrentProgress() throws Exception {
        try (var schema = database.createSchema(); var pool = pool(schema.dataSource(),4)) {
            var definition = definition();
            try (var engine = engine(pool,true,true,definition)) { engine.start("scale","template",Map.of()); }
            try (var c = pool.getConnection()) {
                seed(c);
                for (String table : List.of("workflow_instance","workflow_event","workflow_timer","workflow_inbox","workflow_outbox")) {
                    execute(c,"analyze " + table);
                    String count=scalar(c,"select count(*) from " + table);
                    assertEquals(table.equals("workflow_instance")?"20000":table.equals("workflow_event")?"100000":"30000",count);
                    record(table + "-rows", count);
                }
                // Isolated test schema: compare the same populated dataset before/after V2.
                execute(c,"drop index idx_workflow_instance_active_keyset");
                capturePlans(c,"baseline-");
                execute(c,JdbcSchemaInitializer.read("db/postgresql/migration/V2__active_instance_keyset_index.sql"));
                capturePlans(c,"deployed-");
                record("added-index-bytes",scalar(c,"select pg_relation_size('idx_workflow_instance_active_keyset')"));

            }
            assertStartupModes(pool);
            try(var owner=engine(pool,false,true)) {
                var ports=JdbcWorkflowPersistence.from(owner.connectionFactory());
                var instances=ports.instances();
                var first=instances.findActiveAfter(null,1).get(0).instanceId();
                assertEquals(2,ports.timers().findByWorkflowInstance(first).size());
                var timeline=ports.events().findByWorkflowInstance(first);assertEquals(5,timeline.size());
                assertEquals("x".repeat(256),((Map<?,?>)timeline.get(0).message().payload()).get("body"));
                assertActivePagination(instances);
                for(var queue:JdbcLeaseSupport.Queue.values()) progress(owner.connectionFactory(),queue);
                try(var c=pool.getConnection()) {
                    for(var queue:JdbcLeaseSupport.Queue.values()) {
                        execute(c,"update "+queue.table+" set status_value='CLAIMED',claimed_by='expired',claim_token='expired',claim_until="+EPOCH+"-1 where status_value in ("+queue.ready+",'CLAIMED') and next_attempt_at is null");
                        assertEquals("10000",scalar(c,"select count(*) from "+queue.table+" where claim_token='expired'"));
                    }
                }
                long began=System.nanoTime();
                try(var recovered=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(pool)
                        .initialize(false).timerPolling(false).lazyDefinitionValidation(true)
                        .clock(java.time.Clock.fixed(NOW,java.time.ZoneOffset.UTC)).build()) {
                    assertEquals(0,recovered.startupValidationQueryCount());
                }
                elapsed("expired-backlog-lazy-startup",began,5000);
                try(var c=pool.getConnection()) {
                    for(var queue:JdbcLeaseSupport.Queue.values()) {
                        assertEquals("9000",scalar(c,"select count(*) from "+queue.table+" where status_value='CLAIMED' and claim_token='expired'"));
                        assertEquals("1000",scalar(c,"select count(*) from "+queue.table+" where status_value='RETRY_SCHEDULED' and claim_token is null and next_attempt_at is null"));
                    }
                }
            }
            assertFalse(pool.isClosed(),"Engine must not close host pool");
            assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

    private static void assertStartupModes(HikariDataSource pool) throws Exception {
        long began=System.nanoTime();
        try(var lazy=engine(pool,false,true)) {
            assertEquals(0,lazy.startupValidationQueryCount());
            assertEquals(0,JdbcWorkflowEngine.RESIDENT_WORKFLOW_COUNT);
        }
        elapsed("lazy-startup",began,5000);
        began=System.nanoTime();
        try(var eager=engine(pool,false,false)) {
            assertEquals(79,eager.startupValidationQueryCount());
            assertEquals(128,eager.startupValidationPeakBatchSize());
            assertEquals(0,JdbcWorkflowEngine.RESIDENT_WORKFLOW_COUNT);
        }
        elapsed("eager-startup",began,30000);
    }

    private static void capturePlans(Connection c,String prefix)throws Exception {
        int isolation=c.getTransactionIsolation();
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try { captureReadCommittedPlans(c,prefix); }
        finally { c.setTransactionIsolation(isolation); }
    }

    private static void captureReadCommittedPlans(Connection c,String prefix)throws Exception {
                for (var queue : JdbcLeaseSupport.Queue.values()) {
                    String sql = new PostgresqlDatabaseStrategy(Map.of()).claimBatchSql(queue,"*");
                    explain(c, prefix + queue.name().toLowerCase() + "-claim", sql, statement -> {
                        for (int i=1;i<=3;i++) statement.setBigDecimal(i,new java.math.BigDecimal(EPOCH));
                        statement.setInt(4,64); statement.setString(5,"plan");
                        statement.setBigDecimal(6,new java.math.BigDecimal(EPOCH).add(java.math.BigDecimal.valueOf(60)));
                        if(queue.timer) statement.setBigDecimal(7,new java.math.BigDecimal(EPOCH));
                    });
                }
                String active = " from workflow_instance where status in ('RUNNING','WAITING','FAILED')";
                explain(c,prefix+"active-first","select *" + active + " order by updated_at,id limit 128", s -> {});
                explain(c,prefix+"active-deep","select *" + active + " and (updated_at>"+EPOCH+"+9000 or (updated_at="+EPOCH+"+9000 and id>'00000000-0000-0000-0000-000000000000')) order by updated_at,id limit 128",s -> {});
                for(String key:List.of("business_key","correlation_id")) explain(c,prefix+"route-"+key,
                        "select * from workflow_instance where workflow_key='scale' and "+key+"='bucket-50' and status in ('RUNNING','WAITING','FAILED') order by updated_at,id limit 64",s -> {});
                explain(c,prefix+"timeline","select * from workflow_event where workflow_instance_id=md5('instance-1')::uuid::text order by sequence_number nulls first,id",s -> {});
                explain(c,prefix+"numeric-deadline","select id from workflow_timer where due_at<="+EPOCH+" and status_value='PENDING' order by due_at limit 64",s -> {});
                explain(c,prefix+"active-update-1000","update workflow_instance set updated_at=updated_at+0.000000001 where status='WAITING' and updated_at>="+EPOCH+"+1 and updated_at<="+EPOCH+"+1000",s -> {});
    }

    private static void progress(JdbcConnectionFactory factory,JdbcLeaseSupport.Queue queue) throws Exception {
        var executor=Executors.newFixedThreadPool(4);var barrier=new CyclicBarrier(4);
        Set<String> ids=ConcurrentHashMap.newKeySet();Set<String> tokens=ConcurrentHashMap.newKeySet();
        long began=System.nanoTime();
        try {
            List<Future<Integer>> tasks=new ArrayList<>();
            for(int worker=0;worker<4;worker++) {
                String name="worker-"+worker;
                tasks.add(executor.submit(()->{
                    barrier.await(5,TimeUnit.SECONDS); int count=0;
                    for(int batch=0;batch<4;batch++) {
                        var ports=JdbcWorkflowPersistence.from(factory);
                        var rows=ports.jdbcTransactions().inWriteTransaction(()->switch(queue) {
                            case TIMER -> ports.timers().claimDueFenced(NOW,name,NOW.plusSeconds(60),64).stream()
                                    .map(row->List.of(row.timerId().toString(),row.claimToken())).toList();
                            case INBOX -> ports.inbox().claimEligibleFenced(NOW,name,NOW.plusSeconds(60),64).stream()
                                    .map(row->List.of(row.messageId().toString(),row.claimToken())).toList();
                            case OUTBOX -> ports.outbox().claimEligibleFenced(NOW,name,NOW.plusSeconds(60),64).stream()
                                    .map(row->List.of(row.messageId().toString(),row.claimToken())).toList();
                        });
                        assertEquals(64,rows.size());
                        for(var row:rows){assertTrue(ids.add(row.get(0)));assertTrue(tokens.add(row.get(1)));}
                        count+=rows.size();
                    }
                    return count;
                }));
            }
            for(var task:tasks)assertEquals(256,task.get(30,TimeUnit.SECONDS));
            assertEquals(1024,ids.size());assertEquals(1024,tokens.size());
            try(var c=factory.open()){assertEquals("1024",scalar(c,"select count(*) from "+queue.table+" where status_value='CLAIMED' and claim_token is not null"));}
            elapsed(queue.name()+"-four-worker-1024-claims",began,30000);
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void hostPoolExhaustionRestorationLockTimeoutBackendReplacementAndShutdown() throws Exception {
        try(var schema=database.createSchema()) {
            var pool=pool(schema.dataSource(),1);
            List<String> pids=new ArrayList<>();
            try {
                try(var owner=engine(pool,true,true,definition())) {
                    var factory=owner.connectionFactory();
                    try(var held=pool.getConnection()) {
                        pids.add(scalar(held,"select pg_backend_pid()"));
                        long began=System.nanoTime();assertThrows(SQLTransientConnectionException.class,pool::getConnection);
                        assertTrue(elapsed("pool-exhaustion",began,3000)>=250);
                    }
                    assertPooledTransactionStateAndLockTimeout(factory, pool, schema);
                    long began=System.nanoTime();
                    try(var broken=pool.getConnection();var admin=database.openAdminConnection()) {
                        String pid=scalar(broken,"select pg_backend_pid()");
                        assertEquals("t",scalar(admin,"select pg_terminate_backend("+pid+")"));
                        assertThrows(SQLException.class,()->scalar(broken,"select 1"));
                    }
                    try(var replacement=pool.getConnection()) {
                        String pid=scalar(replacement,"select pg_backend_pid()");assertFalse(pids.contains(pid));pids.add(pid);
                        assertEquals("1",scalar(replacement,"select 1"));
                    }
                    elapsed("backend-replacement",began,5000);
                    assertNotNull(owner.start("scale","after-reconnect",Map.of()));
                }
                assertFalse(pool.isClosed());try(var c=pool.getConnection()){assertEquals("1",scalar(c,"select 1"));}
                Set<Thread> before=Thread.getAllStackTraces().keySet();
                try(var polling=WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(pool)
                        .initialize(false).timerPolling(true).build()) {
                    assertNotNull(polling);
                }
                for(var thread:Thread.getAllStackTraces().keySet()) {
                    if(!before.contains(thread)&&thread.getName().startsWith("jworkflow-jdbc-timers-")) {
                        thread.join(6000);assertFalse(thread.isAlive(),"Timer poller survived engine shutdown");
                    }
                }
            } finally {pool.close();}
            assertTrue(pool.isClosed());assertThrows(SQLException.class,pool::getConnection);
            try(var c=database.openAdminConnection()) {
                assertEquals("0",scalar(c,"select count(*) from pg_stat_activity where pid in ("+String.join(",",pids)+")"));
            }
        }
    }

    @Test void externalSchemaRestrictedRuntimePoolAndIsolation() throws Exception {
        String role="runtime_"+UUID.randomUUID().toString().replace("-","");String password=UUID.randomUUID().toString();
        try(var admin=database.openAdminConnection()) {
            execute(admin,"create role "+role+" login password '"+password+"'");
            try(var first=database.createSchema();var second=database.createSchema()) {
                for(var schema:List.of(first,second)) Flyway.configure().dataSource(schema.dataSource()).locations("classpath:db/postgresql/migration").load().migrate();
                execute(admin,"grant usage on schema "+first.name()+" to "+role);
                try(var c=first.openConnection();var s=c.createStatement();var rows=s.executeQuery("select tablename from pg_tables where schemaname=current_schema() and (tablename like 'workflow_%' or tablename='event_status')")) {
                    while(rows.next())execute(admin,"grant select,insert,update,delete on "+first.name()+"."+rows.getString(1)+" to "+role);
                }
                var runtime=new PGSimpleDataSource();runtime.setURL(((PGSimpleDataSource)first.dataSource()).getURL());
                runtime.setUser(role);runtime.setPassword(password);runtime.setCurrentSchema(first.name());
                runtime.setConnectTimeout(10);runtime.setSocketTimeout(30);runtime.setOptions("-c statement_timeout=10000 -c lock_timeout=5000");
                try(var pool=pool(runtime,2);var engine=engine(pool,false,true,definition())) {
                    var id=engine.start("scale","restricted",Map.of());assertEquals(WorkflowStatus.RUNNING,engine.snapshot(id).status());
                    try(var c=pool.getConnection()) {
                        assertEquals(first.name(),scalar(c,"select current_schema()"));
                        for(String sql:List.of("create table forbidden(id int)","select * from flyway_schema_history","select * from "+second.name()+".workflow_instance"))
                            assertEquals("42501",assertThrows(SQLException.class,()->execute(c,sql)).getSQLState());
                    }
                }
                try(var c=second.openConnection()){assertEquals("0",scalar(c,"select count(*) from workflow_instance"));}
            } finally {execute(admin,"drop owned by "+role);execute(admin,"drop role "+role);}
        }
    }

    private static HikariDataSource pool(DataSource source,int size) {
        HikariConfig config=new HikariConfig();config.setDataSource(source);config.setMaximumPoolSize(size);config.setMinimumIdle(0);
        config.setConnectionTimeout(500);config.setValidationTimeout(250);config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        return new HikariDataSource(config);
    }
    private static WorkflowDefinition definition(){return WorkflowDefinition.of("scale","1","waiting",
            WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("scale.completed"),"employeeId","done"),null),WorkflowNode.end("done"));}
    private static JdbcWorkflowEngine engine(DataSource source,boolean initialize,boolean lazy,WorkflowDefinition...definitions) throws Exception {
        var builder=WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).dataSource(source).initialize(initialize)
                .timerPolling(false).lazyDefinitionValidation(lazy).startupValidationBatchSize(128);
        for(var definition:definitions)builder.definition(definition);
        return (JdbcWorkflowEngine)builder.build();
    }
    private static void seed(Connection c)throws SQLException {
        execute(c,"delete from workflow_outbox");
        execute(c,"insert into workflow_instance select md5('instance-'||g)::uuid::text,workflow_key,workflow_version,workflow_revision,'bucket-'||(g%1000),'bucket-'||(g%1000),current_state,case when g<=10000 then 'WAITING' else 'COMPLETED' end,variables,0,"+EPOCH+"+g,"+EPOCH+"+g,pending_wait_json from workflow_instance cross join generate_series(1,20000) g");
        execute(c,"delete from workflow_command_result");execute(c,"delete from workflow_event");execute(c,"delete from workflow_instance where business_key='template'");
        executePayload(c,"insert into workflow_event(id,event_type,subject,action,workflow_instance_id,occurred_at,received_at,sequence_number,message_payload,message_content_type) select md5('event-'||g)::uuid::text,'scale.completed','scale','completed',md5('instance-'||((g-1)/5+1))::uuid::text,"+EPOCH+"+g,"+EPOCH+"+g,((g-1)%5)+1,?,'application/json' from generate_series(1,100000) g",Map.of("payload",Map.of("body","x".repeat(256)),"attributes",Map.of()));
        execute(c,"insert into workflow_timer(id,workflow_instance_id,timer_type,due_at,status_value,created_at,next_attempt_at,step_name,target_node) select md5('timer-'||g)::uuid::text,md5('instance-'||((g-1)%20000+1))::uuid::text,'STEP_TIMEOUT',"+EPOCH+"-g,case when g<=10000 then 'PENDING' when g<=20000 then 'RETRY_SCHEDULED' else 'FIRED' end,"+EPOCH+"-g,case when g>10000 and g<=20000 then "+EPOCH+"+86400 else null end,'waiting','done' from generate_series(1,30000) g");
        executePayload(c,"insert into workflow_inbox(id,external_event_id,source_system,received_at,status_value,next_attempt_at,message_payload,message_content_type) select md5('inbox-'||g)::uuid::text,'external-'||g,'load',"+EPOCH+"-g,case when g<=10000 then 'RECEIVED' when g<=20000 then 'RETRY_SCHEDULED' else 'PROCESSED' end,case when g>10000 and g<=20000 then "+EPOCH+"+86400 else null end,?,'application/json' from generate_series(1,30000) g",Map.of("body","x".repeat(256)));
        executePayload(c,"insert into workflow_outbox(id,event_id,destination,idempotency_key,created_at,status_value,next_attempt_at,message_payload,message_content_type) select md5('outbox-'||g)::uuid::text,md5('event-'||g)::uuid::text,'load','delivery-'||g,"+EPOCH+"-g,case when g<=10000 then 'PENDING' when g<=20000 then 'RETRY_SCHEDULED' else 'PUBLISHED' end,case when g>10000 and g<=20000 then "+EPOCH+"+86400 else null end,?,'application/json' from generate_series(1,30000) g",Map.of("body","x".repeat(256)));
    }
    @FunctionalInterface private interface Bind {void apply(PreparedStatement statement)throws SQLException;}
    private static void explain(Connection c,String name,String sql,Bind bind)throws Exception {
        List<Double> times=new ArrayList<>();
        for(int sample=0;sample<5;sample++) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("explain (analyze,buffers,wal,format json) "+sql)) {
                bind.apply(s);try(var r=s.executeQuery()) {
                    assertTrue(r.next());String raw=r.getString(1);JsonNode plan=JSON.readTree(raw).get(0);
                    int expected=name.contains("update-1000")?0:name.contains("active-")?128:name.contains("route-")?10:name.contains("timeline")?5:64;
                    assertEquals(expected,plan.get("Plan").get("Actual Rows").asInt(),name);
                    Files.writeString(RESULTS.resolve(name+"-"+sample+".json"),raw);
                    double ms=plan.get("Execution Time").asDouble();times.add(ms);assertTrue(ms<1000,name+" exceeded 1000 ms: "+ms);
                }
            }finally{c.rollback();c.setAutoCommit(true);}
        }
        record(name+"-server-ms",times.toString());
    }
    private static long elapsed(String name,long began,long ceiling)throws Exception {
        long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);record(name+"-ms",Long.toString(ms));assertTrue(ms<ceiling,name+" took "+ms+"ms");return ms;
    }
    private static void record(String name,String value)throws Exception {Files.writeString(RESULTS.resolve(name+".txt"),value+System.lineSeparator());System.out.println("PG12 "+name+" "+value);}
    private static String scalar(Connection c,String sql)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private static void executePayload(Connection c,String sql,Object payload)throws SQLException {
        try(var statement=c.prepareStatement(sql)) {statement.setString(1,new JdbcJsonCodec().write(payload));statement.executeUpdate();}
    }
    private static void execute(Connection c,String sql)throws SQLException {try(var s=c.createStatement()){s.execute(sql);}}
    private static void assertActivePagination(org.jworkflow.persistence.WorkflowInstanceRepository instances) {
                org.jworkflow.persistence.ActiveWorkflowCursor cursor=null;
                Set<WorkflowInstanceId> visited=new HashSet<>();
                while(true) {
                    var page=instances.findActiveAfter(cursor,128);assertTrue(page.size()<=128);
                    for(var snapshot:page) {
                        assertTrue(visited.add(snapshot.instanceId()));
                        assertEquals(123456789,snapshot.updatedAt().getNano());
                    }
                    if(page.size()<128)break;
                    var last=page.get(page.size()-1);cursor=new org.jworkflow.persistence.ActiveWorkflowCursor(last.updatedAt(),last.instanceId());
                }
                assertEquals(10000,visited.size());
    }
    private static void assertPooledTransactionStateAndLockTimeout(JdbcConnectionFactory factory, HikariDataSource pool, PostgresTestDatabase.Schema schema) throws Exception {
                    var tx=new JdbcTransactionManager(factory);
                    tx.inWriteTransaction(()->{try(var c=factory.open()){
                        assertFalse(c.getAutoCommit());assertEquals(Connection.TRANSACTION_READ_COMMITTED,c.getTransactionIsolation());
                        execute(c,"insert into workflow_lock values ('lock','owner',0)");
                    }catch(SQLException e){throw new RuntimeException(e);}return null;});
                    try(var c=pool.getConnection()){
                        assertTrue(c.getAutoCommit());assertFalse(c.isReadOnly());
                        assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());
                    }
                    try(var holder=schema.openConnection();var blocked=pool.getConnection()) {
                        holder.setAutoCommit(false);execute(holder,"update workflow_lock set owner_id='held' where lock_key='lock'");
                        blocked.setAutoCommit(false);execute(blocked,"set local lock_timeout='250ms'");
                        long began=System.nanoTime();
                        assertEquals("55P03",assertThrows(SQLException.class,()->execute(blocked,"update workflow_lock set owner_id='blocked' where lock_key='lock'")).getSQLState());
                        elapsed("row-lock-timeout",began,3000);blocked.rollback();holder.rollback();
                        assertEquals("owner",scalar(blocked,"select owner_id from workflow_lock where lock_key='lock'"));blocked.rollback();
                    }
    }
}
