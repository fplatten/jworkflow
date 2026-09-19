package org.jworkflow.jdbc;

import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.persistence.PersistenceSerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StorageCompatibilityTest {
    @TempDir Path directory;
    @Test void sqliteRetainsPayloadAndExactStringRoundTrips() {
        JdbcWorkflowPersistence persistence=persistence("roundtrip");
        StorageValueContract.payloads(persistence);
        StorageValueContract.exactTimesAndRevisions(persistence);
    }

    @Test void sqliteMixedFractionOrderingIsCharacterizedNotClaimedCorrect() {
        JdbcWorkflowPersistence persistence=persistence("ordering");
        Instant whole=Instant.parse("2026-01-01T00:00:00Z"), fraction=whole.plusNanos(1);
        var timer=StorageValueContract.timer(WorkflowInstanceId.random(),fraction,null);
        persistence.timers().save(timer);
        // Existing ISO string ordering places '.' before 'Z': a future timer is incorrectly due.
        assertEquals(timer.timerId(),persistence.timers().dueTimers(whole).get(0).timerId());
        var first=StorageValueContract.snapshot(WorkflowInstanceId.random(),whole);
        var later=StorageValueContract.snapshot(WorkflowInstanceId.random(),fraction);
        persistence.instances().insert(first); persistence.instances().insert(later);
        assertEquals(later.instanceId(),persistence.instances().findActive(1).get(0).instanceId());
    }

    @Test void numericInstantCodecRejectsPrecisionAndRangeLoss() {
        for (Instant instant : StorageValueContract.TIMES) assertEquals(instant,PostgresqlInstantCodec.decode(PostgresqlInstantCodec.encode(instant)));
        assertEquals(new BigDecimal("-0.000000001"),PostgresqlInstantCodec.encode(Instant.ofEpochSecond(-1,999_999_999)));
        assertNull(PostgresqlInstantCodec.decode(null));
        for (BigDecimal invalid : new BigDecimal[]{new BigDecimal("0.0000000001"), new BigDecimal("999999999999999999999.000000000"),
                PostgresqlInstantCodec.encode(Instant.MIN).subtract(new BigDecimal("0.000000001")),
                PostgresqlInstantCodec.encode(Instant.MAX).add(new BigDecimal("0.000000001"))}) {
            assertThrows(PersistenceSerializationException.class,()->PostgresqlInstantCodec.decode(invalid));
        }
    }

    @Test void legacySqliteResourcesRetainPublishedByteChecksums() throws Exception {
        Map<String,String> hashes=Map.of(
                "V1__create_jworkflow_schema.sql","915594d6c3c55d3ef081fbefc3a87aa27d249535b4369f32a7a78e67f37cf0be",
                "V2__durable_workflow_and_messaging.sql","eb10b825a6f1048039ece662d135d6dbb1f1fa4b2236c4c296253786b02c7354",
                "V3__timer_attempt_history.sql","e538d59f269eee1bba37f84bf38e505c6f0d098ef5eaa7bbd4a47c984fe99b93",
                "V4__event_routing_indexes.sql","6909b0acdf0a277405c0f793d3eb23ab38a0a6d9b1f0d70b1f53b2528ae3dade",
                "V5__message_redaction_status.sql","0b665792cab8ee4c7da01e6e6cb00161849c61cf67b413986ccc696e465f626e");
        for(var entry:hashes.entrySet()) assertEquals(entry.getValue(),JdbcSchemaInitializer.sha256(JdbcSchemaInitializer.read("db/migration/"+entry.getKey())),entry.getKey());
    }

    private JdbcWorkflowPersistence persistence(String name) {
        return JdbcWorkflowPersistence.create("jdbc:sqlite:"+directory.resolve(name+".db"),null,null,new org.sqlite.JDBC(),null,true,Map.of());
    }
}
