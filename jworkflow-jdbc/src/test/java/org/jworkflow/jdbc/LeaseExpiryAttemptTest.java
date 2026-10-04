package org.jworkflow.jdbc;
import org.jworkflow.internal.model.WorkflowTimer;

import org.jworkflow.inbox.*;
import org.jworkflow.outbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.LeaseContract.*;

/** An abandoned lease is a failed attempt, so work that crashes or hangs its worker cannot be redelivered forever. */
class LeaseExpiryAttemptTest {
    @TempDir Path directory;

    @TestFactory Stream<DynamicTest> expiredLeasesCountAttemptsAndDeadLetterWhenExhausted(){
        return Arrays.stream(Kind.values()).map(kind->DynamicTest.dynamicTest(kind.name(),()->{
            JdbcWorkflowPersistence p=persistence(kind+"-release.db");
            UUID id=seed(p,kind);

            acquire(p,kind,NOW,"crashed-1",NOW.plusSeconds(1),1);
            assertEquals(1,inWrite(p,()->release(p,kind,NOW.plusSeconds(2))));
            Lease second=acquire(p,kind,NOW.plusSeconds(2),"crashed-2",NOW.plusSeconds(3),1).get(0);
            assertEquals(1,attempts(second),"expired lease must count as an attempt");

            assertEquals(1,inWrite(p,()->release(p,kind,NOW.plusSeconds(4))));
            if(kind==Kind.TIMER){
                Lease third=acquire(p,kind,NOW.plusSeconds(4),"worker",NOW.plusSeconds(5),1).get(0);
                assertEquals(2,attempts(third));
                return;
            }
            assertTrue(acquire(p,kind,NOW.plusSeconds(4),"worker",NOW.plusSeconds(5),1).isEmpty(),
                    "exhausted message must not be redelivered");
            Object stored=kind==Kind.INBOX?p.inbox().findById(id).orElseThrow():p.outbox().findById(id).orElseThrow();
            if(stored instanceof InboxMessage m){
                assertEquals(InboxMessageStatus.DEAD_LETTER,m.status());assertEquals(2,m.attemptCount());
                assertEquals(JdbcLeaseSupport.LEASE_EXPIRED_ERROR,m.lastError());
            }else if(stored instanceof OutboxMessage m){
                assertEquals(OutboxMessageStatus.DEAD_LETTER,m.status());assertEquals(2,m.attemptCount());
                assertEquals(JdbcLeaseSupport.LEASE_EXPIRED_ERROR,m.lastError());
            }
        }));
    }

    @TestFactory Stream<DynamicTest> directlyReclaimedExpiredLeaseCountsAttempt(){
        return Arrays.stream(Kind.values()).map(kind->DynamicTest.dynamicTest(kind.name(),()->{
            JdbcWorkflowPersistence p=persistence(kind+"-reclaim.db");
            seed(p,kind);
            Lease first=acquire(p,kind,NOW,"crashed",NOW.plusSeconds(1),1).get(0);
            assertEquals(0,attempts(first));
            Lease reclaimed=acquire(p,kind,NOW.plusSeconds(2),"worker",NOW.plusSeconds(3),1).get(0);
            assertEquals(1,attempts(reclaimed),"re-acquiring an expired lease must count the abandoned attempt");
        }));
    }

    private JdbcWorkflowPersistence persistence(String file){
        var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(file));
        JdbcWorkflowPersistence p=JdbcWorkflowPersistence.create(null,null,null,null,source,true,Map.of());
        p.configureLeaseAttemptLimits(Map.of("inbox.max-attempts","2","outbox.max-attempts","2"));
        return p;
    }
    private static <T> T inWrite(JdbcWorkflowPersistence p,java.util.function.Supplier<T> work){
        return p.jdbcTransactions().inWriteTransaction(work::get);
    }
    private static int attempts(Lease lease){
        if(lease.value() instanceof WorkflowTimer t)return t.attemptCount();
        if(lease.value() instanceof InboxMessage m)return m.attemptCount();
        return ((OutboxMessage)lease.value()).attemptCount();
    }
}
