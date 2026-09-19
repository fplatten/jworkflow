package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.persistence.*;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.DuplicateRepositoryContract.*;

@Timeout(120)
class DuplicatesPostgresIT {
    static PostgresTestDatabase database;
    @BeforeAll static void start(){database=PostgresTestDatabase.start();}
    @AfterAll static void stop()throws Exception{if(database!=null)database.close();}

    @Test void binaryJsonAndEnvelopeDuplicatesValidateByStoredContent()throws Exception {
        try(var schema=database.createSchema()) {
            var ports=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            ports.jdbcTransactions().inTransaction(()->{envelopeAndTypeConflicts(ports);return null;});
        }
    }

    @Test void inboxDuplicateReturnsFirstArrivalIncludingItsCurrentLifecycleState()throws Exception {
        try(var schema=database.createSchema()) {
            var ports=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            var first=(org.jworkflow.inbox.InboxMessage)sample(Kind.INBOX,"identity");save(ports,first);
            ports.jdbcTransactions().inWriteTransaction(()->{
                var claim=ports.inbox().claimEligibleFenced(NOW,"worker",NOW.plusSeconds(60),1).get(0);
                ports.inbox().markProcessed(first.messageId(),"worker",claim.claimToken(),NOW);return null;
            });
            var redelivery=new org.jworkflow.inbox.InboxMessage(UUID.randomUUID(),first.externalEventId(),first.sourceSystem(),
                    org.jworkflow.events.EventMessage.json("different transport body"),"new-correlation",null,NOW.plusSeconds(10),null,null,0,null,null,null,null);
            var result=ports.inbox().insertIfAbsent(redelivery);
            assertFalse(result.inserted());assertEquals(first.messageId(),result.message().messageId());
            assertEquals(first.message(),result.message().message());assertEquals(first.correlationId(),result.message().correlationId());
            assertEquals(org.jworkflow.inbox.InboxMessageStatus.PROCESSED,result.message().status());
        }
    }

    @Test void propagatedNestedConflictsMarkOuterWorkRollbackOnly()throws Exception {
        for(Kind kind:Kind.values())try(var schema=database.createSchema()) {
            var ports=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            Object first=sample(kind,"nested-conflict");save(ports,first);
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,schema.dataSource(),Map.of());factory.strategy();
            var tested=JdbcWorkflowPersistence.from(factory);var tx=tested.jdbcTransactions();
            try(Connection setup=schema.openConnection();Statement statement=setup.createStatement()){statement.execute("create table pg07_marker(id integer)");}
            var failure=assertThrows(WorkflowPersistenceException.class,()->tx.inTransaction(()->{
                execute(factory,"insert into pg07_marker values(1)");
                assertThrows(RuntimeException.class,()->tx.inTransaction(()->save(tested,duplicate(first,true))));
                if(kind!=Kind.INBOX)assertEquals(1,scalar(factory,"select 1"));return null;
            }));
            assertNotNull(failure.getCause());assertEquals(0,TransactionNotificationContract.count(schema.dataSource(),"pg07_marker"));assertEquals(first,find(ports,first));
        }
    }

    @TestFactory Stream<DynamicTest> independentConnectionDuplicateRaces() {
        List<DynamicTest> cases=new ArrayList<>();
        for(Kind kind:Kind.values())for(boolean nested:List.of(false,true))for(boolean conflict:List.of(false,true))for(boolean rollback:List.of(false,true)) {
            cases.add(DynamicTest.dynamicTest(kind+" nested="+nested+" conflict="+conflict+" winnerRollback="+rollback,
                    ()->race(kind,nested,conflict,rollback)));
        }
        return cases.stream();
    }

    private void race(Kind kind,boolean nested,boolean conflict,boolean rollback)throws Exception {
        race(kind,nested,conflict,rollback,false);
    }

    @TestFactory Stream<DynamicTest> identicalPrimaryAndNaturalKeyRaces() {
        List<DynamicTest> cases=new ArrayList<>();
        for(Kind kind:List.of(Kind.INBOX,Kind.OUTBOX))for(boolean nested:List.of(false,true))for(boolean rollback:List.of(false,true))
            cases.add(DynamicTest.dynamicTest("same row "+kind+" nested="+nested+" rollback="+rollback,
                    ()->race(kind,nested,false,rollback,true)));
        return cases.stream();
    }

    private void race(Kind kind,boolean nested,boolean conflict,boolean rollback,boolean sameId)throws Exception {
        try(var schema=database.createSchema()) {
            var winner=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            try(Connection setup=schema.openConnection();Statement statement=setup.createStatement()){statement.execute("create table pg07_contender(id integer)");}
            var tracked=new ObservedSource(schema.dataSource());
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,tracked.source,Map.of());
            factory.strategy();tracked.opens.set(0);
            var loser=JdbcWorkflowPersistence.from(factory);
            Object first=sample(kind,"race"),attempt=sameId?first:duplicate(first,conflict);
            CountDownLatch inserted=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger winnerPid=new AtomicInteger();
            ExecutorService executor=Executors.newFixedThreadPool(2);
            RuntimeException abort=new IllegalStateException("winner rolls back");
            try {
                Future<?> holder=executor.submit(()->{
                    try {winner.jdbcTransactions().inTransaction(()->{
                        // Obtain the bound session through a repository-independent probe using this bundle's transaction.
                        save(winner,first);
                        inserted.countDown();await(release);if(rollback)throw abort;return null;
                    });}catch(RuntimeException failure){if(failure!=abort)throw failure;}
                });
                assertTrue(inserted.await(10,TimeUnit.SECONDS));
                Future<Object> contender=executor.submit(()->{
                    java.util.function.Supplier<Object> operation=()->{
                        try {
                            Saved saved=save(loser,attempt);
                            if(nested){assertEquals(1,scalar(factory,"select 1"));execute(factory,"insert into pg07_contender values(1)");}
                            return saved;
                        } catch(PersistenceConstraintException validation) {
                            // Logical immutable-content conflicts did not abort PostgreSQL's transaction.
                            if(nested){assertEquals(1,scalar(factory,"select 1"));execute(factory,"insert into pg07_contender values(1)");}
                            return validation;
                        }
                    };
                    try{return nested?loser.jdbcTransactions().inTransaction(()->loser.jdbcTransactions().inTransaction(operation::get)):operation.get();}
                    catch(WorkflowInfrastructureException sqlFailure){return sqlFailure;}
                });
                try(Connection observer=schema.openConnection()) {
                    winnerPid.set(awaitBlocking(observer,tracked.application));
                    assertTrue(winnerPid.get()>0);assertFalse(contender.isDone(),"contender must actually wait on the uncommitted winner");
                }
                release.countDown();holder.get(15,TimeUnit.SECONDS);
                Object outcome=contender.get(15,TimeUnit.SECONDS);
                if(conflict&&!rollback) {
                    if(kind==Kind.INBOX) {
                        assertInstanceOf(WorkflowInfrastructureException.class,outcome);
                        assertEquals(JdbcTransactionException.Category.CONSTRAINT,JdbcTransactionException.classify((Throwable)outcome));
                    } else assertInstanceOf(PersistenceConstraintException.class,outcome);
                } else {
                    if(outcome instanceof Throwable failure)throw new AssertionError(kind+" nested="+nested+" conflict="+conflict+" rollback="+rollback,failure);
                    Saved actual=assertInstanceOf(Saved.class,outcome);
                    assertEquals(rollback?attempt:first,actual.value());
                    if(kind==Kind.INBOX)assertEquals(rollback,actual.inserted());
                }
                assertEquals(1,tracked.opens.get(),"one physical borrow for INSERT and follow-up SELECT");
                if(!rollback&&!(kind==Kind.INBOX&&conflict)) {
                    if(sameId)assertTrue(tracked.statements.size()>=2,"primary-key recovery and winner lookup use fresh statements");
                    else assertEquals(2,tracked.statements.size(),"winner lookup must be a separate statement with a new snapshot");
                    assertTrue(tracked.statements.get(0).startsWith("insert into "+kind.table));
                    assertTrue(tracked.statements.get(1).startsWith("select "));
                }
                assertEquals(rollback?attempt:first,find(winner,rollback?attempt:first));
                assertEquals(1,TransactionNotificationContract.count(schema.dataSource(),kind.table));
                assertEquals(nested&&!(conflict&&!rollback&&kind==Kind.INBOX)?1:0,TransactionNotificationContract.count(schema.dataSource(),"pg07_contender"));
                assertEquals(1,scalar(factory,"select 1"),"fresh direct borrow remains usable");
                assertNull(factory.currentTransactionConnection());
            }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
        }
    }

    @TestFactory Stream<DynamicTest> unexpectedConstraintsAreNeverRecoveredAsDuplicates() {
        List<DynamicTest> cases=new ArrayList<>();
        for(Kind kind:Kind.values())for(boolean nested:List.of(false,true))for(String constraint:List.of("unique","foreign","null","check"))
            cases.add(DynamicTest.dynamicTest(kind+" nested="+nested+" "+constraint,()->unexpected(kind,nested,constraint)));
        return cases.stream();
    }

    private void unexpected(Kind kind,boolean nested,String constraint)throws Exception {
        try(var schema=database.createSchema()) {
            var ports=JdbcWorkflowPersistence.create(null,null,null,null,schema.dataSource(),true,Map.of());
            try(Connection connection=schema.openConnection();Statement statement=connection.createStatement()) {
                statement.execute("create table pg07_marker(id integer)");
                switch(constraint) {
                    case "unique" -> statement.execute("alter table "+kind.table+" add column pg07_guard integer default 1 unique");
                    case "foreign" -> {statement.execute("create table pg07_parent(id integer primary key)");statement.execute("alter table "+kind.table+" add column pg07_guard integer default 1 references pg07_parent(id)");}
                    case "null" -> statement.execute("alter table "+kind.table+" add column pg07_guard integer not null");
                    case "check" -> statement.execute("alter table "+kind.table+" add constraint pg07_check check(false)");
                }
            }
            if(constraint.equals("unique"))save(ports,sample(kind,"existing"));
            var tracked=new ObservedSource(schema.dataSource());
            var factory=new JdbcConnectionFactory(WorkflowEngine.Type.POSTGRESQL,null,null,null,null,tracked.source,Map.of());factory.strategy();
            var tested=JdbcWorkflowPersistence.from(factory);Object value=sample(kind,"new");
            java.util.function.Supplier<Object> call=()->{
                if(nested)execute(factory,"insert into pg07_marker values(1)");
                try {return save(tested,value);}
                catch(WorkflowInfrastructureException failure) {
                    assertEquals(switch(constraint){case "unique"->"23505";case "foreign"->"23503";case "null"->"23502";default->"23514";},sqlCause(failure).getSQLState());
                    assertEquals(1,tracked.statements.stream().filter(sql->sql.startsWith("insert into "+kind.table)).count());
                    assertFalse(tracked.statements.stream().anyMatch(sql->sql.startsWith("select ")&&sql.contains("from "+kind.table)),"no recovery SELECT after SQL failure");
                    if(nested) {
                        RuntimeException aborted=assertThrows(RuntimeException.class,()->scalar(factory,"select 1"));
                        assertEquals("25P02",sqlCause(aborted).getSQLState());
                    }
                    throw failure;
                }
            };
            assertThrows(WorkflowInfrastructureException.class,()->{if(nested)tested.jdbcTransactions().inTransaction(call::get);else call.get();});
            assertEquals(constraint.equals("unique")?1:0,TransactionNotificationContract.count(schema.dataSource(),kind.table));
            assertEquals(0,TransactionNotificationContract.count(schema.dataSource(),"pg07_marker"));
            assertEquals(1,scalar(factory,"select 1"));
        }
    }

    private static SQLException sqlCause(Throwable failure) {
        for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof SQLException sql)return sql;
        throw new AssertionError("Expected SQL cause",failure);
    }
    private static int scalar(JdbcConnectionFactory factory,String sql) {
        try(Connection connection=factory.open();Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(sql)){
            assertTrue(rows.next());return rows.getInt(1);
        }catch(SQLException failure){throw new WorkflowInfrastructureException("test query",failure);}
    }
    private static void execute(JdbcConnectionFactory factory,String sql) {
        try(Connection connection=factory.open();Statement statement=connection.createStatement()){statement.execute(sql);}
        catch(SQLException failure){throw new WorkflowInfrastructureException("test write",failure);}
    }
    private static void await(CountDownLatch latch) {
        try{assertTrue(latch.await(15,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }
    private static int awaitBlocking(Connection observer,String application)throws SQLException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        try(PreparedStatement statement=observer.prepareStatement("select pid,(pg_blocking_pids(pid))[1] from pg_stat_activity where application_name=? and wait_event_type='Lock'")) {
            statement.setString(1,application);
            do {
                try(ResultSet rows=statement.executeQuery()){if(rows.next()){int pid=rows.getInt(1),blocker=rows.getInt(2);assertNotEquals(pid,blocker);return blocker;}}
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }while(System.nanoTime()<deadline);
        }
        fail("Contender never waited on the independent winner session");return 0;
    }

    static final class ObservedSource {
        final String application="pg07-"+UUID.randomUUID();
        final AtomicInteger opens=new AtomicInteger();
        final List<String> statements=new CopyOnWriteArrayList<>();
        final DataSource source;
        ObservedSource(DataSource delegate) {
            source=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
                if(!m.getName().equals("getConnection"))return invoke(delegate,m,a);
                Connection connection=delegate.getConnection();opens.incrementAndGet();connection.setClientInfo("ApplicationName",application);
                return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                    if(cm.getName().equals("prepareStatement"))statements.add(((String)ca[0]).stripLeading());
                    return invoke(connection,cm,ca);
                });
            });
        }
        private static Object invoke(Object target,Method method,Object[] args)throws Throwable {
            try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        }
    }
}
