package org.jworkflow.persistence;

import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowInstanceRepositoryDefaultsTest {
    @Test
    void defaultQueriesFilterAndValidateTheirInputs() {
        Instant now = Instant.parse("2026-09-17T12:00:00Z");
        WorkflowSnapshot running = snapshot("orders", "one", "corr", WorkflowStatus.RUNNING, 0, now.minusSeconds(20));
        WorkflowSnapshot waiting = snapshot("orders", "two", "corr", WorkflowStatus.WAITING, 1, now.minusSeconds(10));
        WorkflowSnapshot failed = snapshot("other", "three", "other", WorkflowStatus.FAILED, 2, now);
        WorkflowSnapshot completed = snapshot("orders", "four", "corr", WorkflowStatus.COMPLETED, 3, now);
        RecordingRepository repository = new RecordingRepository(List.of(running, waiting, failed, completed));

        assertEquals(running, repository.findActiveById(running.instanceId()).orElseThrow());
        assertTrue(repository.findActiveById(completed.instanceId()).isEmpty());
        assertEquals(List.of(running, waiting), repository.findActiveByCorrelation("orders", "corr", 10));
        assertEquals(List.of(running), repository.findActiveByBusinessKey("orders", "one", 10));
        assertEquals(running, repository.findByBusinessKey("orders", "one").orElseThrow());
        assertEquals(List.of(running, waiting, failed), repository.findActiveAfter(null, 10));
        assertEquals(List.of(running, waiting, failed), repository.findAll(10, 50));
        assertEquals(List.of(waiting), repository.findByStatus(WorkflowStatus.WAITING, 10));
        assertEquals(List.of(running, waiting), repository.findStuck(now.minusSeconds(5), 10));
        assertEquals(waiting, repository.find(waiting.instanceId()).orElseThrow());

        repository.save(running);
        repository.save(waiting);
        assertEquals(1, repository.inserts);
        assertEquals(1, repository.updates);
        assertEquals(0, repository.lastExpectedVersion);

        assertThrows(IllegalArgumentException.class,
                () -> repository.findActiveByCorrelation("orders", "corr", 0));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findActiveByBusinessKey("orders", "one", -1));
    }

    private static WorkflowSnapshot snapshot(String key, String businessKey, String correlation,
            WorkflowStatus status, long version, Instant updatedAt) {
        return new WorkflowSnapshot(WorkflowInstanceId.random(), key, "1", "revision", businessKey,
                correlation, "state", status, Map.of(), version, updatedAt.minusSeconds(1), updatedAt);
    }

    private static final class RecordingRepository implements WorkflowInstanceRepository {
        private final List<WorkflowSnapshot> snapshots;
        private int inserts;
        private int updates;
        private long lastExpectedVersion = -1;

        private RecordingRepository(List<WorkflowSnapshot> snapshots) {
            this.snapshots = new ArrayList<>(snapshots);
        }

        @Override public void insert(WorkflowSnapshot snapshot) { inserts++; }

        @Override public WorkflowSnapshot update(WorkflowSnapshot snapshot, long expectedLockVersion) {
            updates++;
            lastExpectedVersion = expectedLockVersion;
            return snapshot;
        }

        @Override public Optional<WorkflowSnapshot> findById(WorkflowInstanceId instanceId) {
            return snapshots.stream().filter(value -> value.instanceId().equals(instanceId)).findFirst();
        }

        @Override public Optional<WorkflowSnapshot> findByCorrelationId(String correlationId) {
            return snapshots.stream().filter(value -> correlationId.equals(value.correlationId())).findFirst();
        }

        @Override public List<WorkflowSnapshot> findActive(int limit) {
            return snapshots.stream().filter(value -> value.status() != WorkflowStatus.COMPLETED).toList();
        }
    }
}
