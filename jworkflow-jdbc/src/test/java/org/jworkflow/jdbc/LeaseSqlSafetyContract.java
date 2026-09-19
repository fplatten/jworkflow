package org.jworkflow.jdbc;

import org.jworkflow.persistence.StaleWorkflowClaimException;
import javax.sql.DataSource;
import java.util.List;
import static org.jworkflow.jdbc.LeaseContract.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises SQL-looking runtime data through the production claim and fenced transition paths. */
final class LeaseSqlSafetyContract {
    private LeaseSqlSafetyContract() { }

    static void assertBoundValues(JdbcWorkflowPersistence persistence, DataSource source, Kind kind) throws Exception {
        seed(persistence, kind);
        seed(persistence, kind);
        String owner = "worker' OR 1=1; DROP TABLE workflow_outbox; --";
        String forgedGuard = "' OR '1'='1' --";
        var until = NOW.plusSeconds(10);
        List<Lease> claimed = acquire(persistence, kind, NOW, owner, until, 1);
        assertEquals(1, claimed.size(), "bound limit still limits the batch");
        Lease lease = claimed.get(0);
        assertEquals(owner, lease.owner(), "owner round-trips literally");
        Lease forgedOwner = new Lease(lease.id(), forgedGuard, lease.token(), lease.value());
        Lease forgedToken = new Lease(lease.id(), owner, forgedGuard, lease.value());
        assertThrows(StaleWorkflowClaimException.class, () -> finish(persistence, kind, forgedOwner, 0, NOW, false));
        assertThrows(StaleWorkflowClaimException.class, () -> finish(persistence, kind, forgedToken, 0, NOW, false));

        String error = "failure',status_value='PUBLISHED'; DROP TABLE workflow_inbox; --";
        persistence.jdbcTransactions().inWriteTransaction(() -> {
            require(persistence, kind, lease);
            switch (kind) {
                case TIMER -> persistence.timers().markFailed(lease.id(), owner, lease.token(), error, until);
                case INBOX -> persistence.inbox().scheduleRetry(lease.id(), owner, lease.token(), until, error);
                case OUTBOX -> persistence.outbox().scheduleRetry(lease.id(), owner, lease.token(), until, error);
            }
            return null;
        });
        assertStoredValues(source, kind, lease, error);
    }

    private static void assertStoredValues(DataSource source, Kind kind, Lease lease, String error) throws Exception {
        // Table names come exclusively from the test's fixed Kind enum; the selected ID is bound.
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("select status_value,last_error_message,claimed_by,claim_token from " + kind.table() + " where id=?")) {
            statement.setString(1, lease.id().toString());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("RETRY_SCHEDULED", rows.getString(1));
                assertEquals(error, rows.getString(2));
                assertNull(rows.getString(3));
                assertNull(rows.getString(4));
                assertFalse(rows.next());
            }
        }
        assertEquals(2, TransactionNotificationContract.count(source, kind.table()));
        String untouched = kind == Kind.INBOX ? "RECEIVED" : "PENDING";
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("select status_value,claimed_by,claim_token from " + kind.table() + " where id<>?")) {
            statement.setString(1, lease.id().toString());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(untouched, rows.getString(1), "forged guards must not update another row");
                assertNull(rows.getString(2));
                assertNull(rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }
}
