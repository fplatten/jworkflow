package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.EventMessage;
import org.jworkflow.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.LeaseContract.*;

/** Upgrade a genuinely populated V5 database with all workers stopped before the additive V6. */
class LeaseUpgradeTest {
    @TempDir Path directory;
    @Test void populatedV5UpgradeRetainsPayloadsClaimsHistoryAndRestartRecovery()throws Exception {
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("v5.db"));
        List<String> names=List.of("create_jworkflow_schema","durable_workflow_and_messaging","timer_attempt_history","event_routing_indexes","message_redaction_status");
        Map<Integer,String> original=new LinkedHashMap<>();UUID id=UUID.randomUUID(),instance=UUID.randomUUID();byte[] binary={0,-1,2,0,127};
        try(Connection connection=source.getConnection();Statement statement=connection.createStatement()){
            connection.setAutoCommit(false);
            statement.execute("create table jworkflow_schema_history(version integer primary key,description varchar(255) not null,checksum varchar(64) not null,installed_at varchar(64) not null)");
            for(int version=1;version<=5;version++){
                String sql=JdbcSchemaInitializer.read("db/migration/V"+version+"__"+names.get(version-1)+".sql");
                for(String command:JdbcSchemaInitializer.statements(sql))statement.execute(command);
                String checksum=JdbcSchemaInitializer.sha256(sql);original.put(version,checksum);
                try(PreparedStatement insert=connection.prepareStatement("insert into jworkflow_schema_history values(?,?,?,?)")){
                    insert.setInt(1,version);insert.setString(2,names.get(version-1).replace('_',' '));insert.setString(3,checksum);insert.setString(4,NOW.toString());insert.executeUpdate();
                }
            }
            try(PreparedStatement insert=connection.prepareStatement("insert into workflow_timer(id,workflow_instance_id,timer_type,step_name,target_node,due_at,next_attempt_at,status_value,created_at,updated_at,claimed_by,claim_until,attempt_count) values(?,?,'STEP_TIMEOUT','waiting','done',?,?,'CLAIMED',?,?,'old-worker',?,4)")){
                insert.setString(1,id.toString());insert.setString(2,instance.toString());for(int i=3;i<=6;i++)insert.setString(i,NOW.toString());insert.setString(7,NOW.plusSeconds(10).toString());insert.executeUpdate();
            }
            try(PreparedStatement insert=connection.prepareStatement("insert into workflow_inbox(id,external_event_id,source_system,received_at,status_value,message_payload,message_metadata_json,message_content_type,message_redaction_status,claimed_by,claim_until,attempt_count) values(?,'external','source',?,'CLAIMED',?,?,'application/json','REDACTED','old-worker',?,4)")){
                insert.setString(1,id.toString());insert.setString(2,NOW.toString());insert.setString(3,new JdbcJsonCodec().write(Map.of("text","雪","nested",List.of(1,true))));insert.setString(4,new JdbcJsonCodec().write(Map.of("retained","yes")));insert.setString(5,NOW.plusSeconds(10).toString());insert.executeUpdate();
            }
            try(PreparedStatement insert=connection.prepareStatement("insert into workflow_outbox(id,event_id,destination,idempotency_key,created_at,status_value,message_payload_blob,message_content_type,message_metadata_json,claimed_by,claim_until,attempt_count) values(?,?,'destination','key',?,'CLAIMED',?,'application/octet-stream',?,'old-worker',?,4)")){
                insert.setString(1,id.toString());insert.setString(2,UUID.randomUUID().toString());insert.setString(3,NOW.toString());insert.setBytes(4,binary);insert.setString(5,new JdbcJsonCodec().write(Map.of("retained","yes")));insert.setString(6,NOW.plusSeconds(10).toString());insert.executeUpdate();
            }
            for(String table:List.of("workflow_timer","workflow_inbox","workflow_outbox")){
                try(ResultSet columns=statement.executeQuery("pragma table_info("+table+")")){while(columns.next())assertNotEquals("claim_token",columns.getString("name"));}
            }
            connection.commit();
        }
        // No live V5 workers are present: old code cannot participate in generation fencing.
        for(int restart=0;restart<2;restart++)try(var engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).dataSource(source).initialize(true).timerPolling(false).clock(Clock.fixed(NOW.plusSeconds(9),ZoneOffset.UTC)).build()){
            var p=JdbcWorkflowPersistence.from(engine.connectionFactory());assertEquals(6,TransactionNotificationContract.count(source,"jworkflow_schema_history"));
            var inbox=p.inbox().findById(id).orElseThrow();assertEquals(Map.of("text","雪","nested",List.of(1,true)),inbox.message().payload());assertTrue(inbox.message().redacted());assertEquals(Map.of("retained","yes"),inbox.message().attributes());
            assertArrayEquals(binary,(byte[])p.outbox().findById(id).orElseThrow().message().payload());
            assertEquals(4,inbox.attemptCount());assertNull(inbox.claimToken());assertEquals("old-worker",inbox.claimedBy());
            for(Kind kind:Kind.values())assertTrue(acquire(p,kind,NOW.plusSeconds(9),"worker",NOW.plusSeconds(20),1).isEmpty());
            try(Connection connection=source.getConnection();Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("select version,checksum from jworkflow_schema_history where version<=5 order by version")){
                Map<Integer,String> actual=new LinkedHashMap<>();while(rows.next())actual.put(rows.getInt(1),rows.getString(2));assertEquals(original,actual);
            }
        }
        try(var engine=(JdbcWorkflowEngine)WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).dataSource(source).initialize(true).timerPolling(false).clock(Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC)).build()){
            var p=JdbcWorkflowPersistence.from(engine.connectionFactory());
            for(Kind kind:Kind.values()){
                var lease=acquire(p,kind,NOW.plusSeconds(10),"old-worker",NOW.plusSeconds(20),1).get(0);assertEquals(id,lease.id());assertNotNull(lease.token());
                p.jdbcTransactions().inWriteTransaction(()->{require(p,kind,lease);finish(p,kind,lease,0,NOW.plusSeconds(10),false);return null;});
            }
            assertArrayEquals(binary,(byte[])p.outbox().findById(id).orElseThrow().message().payload());
        }
    }
}
