package org.jworkflow.jdbc;

import org.jworkflow.events.EventMessage;
import org.jworkflow.model.WorkflowInstanceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Direct SQLite appends retain serialized allocation without requiring an engine transaction. */
class EventSequenceTest {
    @TempDir Path directory;
    @Test void concurrentDirectSqliteAppendsAndFailedInsertKeepContiguousNumbers()throws Exception {
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("events.db"));
        var ports=JdbcWorkflowPersistence.create(null,null,null,null,source,true,Map.of());
        var id=WorkflowInstanceId.random();var gate=new CyclicBarrier(4);var executor=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int thread=0;thread<4;thread++)futures.add(executor.submit(()->{
                try{gate.await(10,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError(failure);}
                for(int i=0;i<5;i++)ports.events().append(StorageValueContract.event(id,EventMessage.empty(),Instant.now()));
            }));
            for(var future:futures)future.get(20,TimeUnit.SECONDS);
            var duplicate=ports.events().findByWorkflowInstance(id).get(0);
            assertThrows(org.jworkflow.engine.WorkflowInfrastructureException.class,()->ports.events().append(duplicate));
            ports.events().append(StorageValueContract.event(id,EventMessage.empty(),Instant.now()));
            try(Connection connection=source.getConnection();Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("select count(*),count(distinct sequence_number),min(sequence_number),max(sequence_number) from workflow_event")) {
                assertTrue(rows.next());assertEquals(21,rows.getInt(1));assertEquals(21,rows.getInt(2));assertEquals(1,rows.getInt(3));assertEquals(21,rows.getInt(4));
            }
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    }
}
