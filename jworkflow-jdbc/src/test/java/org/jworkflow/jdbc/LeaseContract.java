package org.jworkflow.jdbc;

import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.outbox.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Semantic lease contracts shared by PostgreSQL and SQLite. */
final class LeaseContract {
    static final Instant NOW=Instant.parse("2026-09-18T12:00:00.123456789Z");
    enum Kind { TIMER,INBOX,OUTBOX;
        String table(){return "workflow_"+name().toLowerCase(Locale.ROOT);}
    }
    record Lease(UUID id,String owner,String token,Object value){ }
    static UUID seed(JdbcWorkflowPersistence p,Kind kind){
        return switch(kind){
            case TIMER->{var m=StorageValueContract.timer(WorkflowInstanceId.random(),NOW,null);p.timers().save(m);yield m.timerId();}
            case INBOX->{var m=StorageValueContract.inbox(EventMessage.json(Map.of("unicode","雪")),NOW);p.inbox().insertIfAbsent(m);yield m.messageId();}
            case OUTBOX->{var m=StorageValueContract.outbox(new EventMessage(new byte[]{0,1,-1},"application/octet-stream",null,null,false,Map.of()),NOW);p.outbox().enqueue(m);yield m.messageId();}
        };
    }
    static List<Lease> claim(JdbcWorkflowPersistence p,Kind kind,Instant now,String owner,Instant until,int limit){
        return switch(kind){
            case TIMER->p.timers().claimDueFenced(now,owner,until,limit).stream().map(m->new Lease(m.timerId(),m.claimedBy(),m.claimToken(),m)).toList();
            case INBOX->p.inbox().claimEligibleFenced(now,owner,until,limit).stream().map(m->new Lease(m.messageId(),m.claimedBy(),m.claimToken(),m)).toList();
            case OUTBOX->p.outbox().claimEligibleFenced(now,owner,until,limit).stream().map(m->new Lease(m.messageId(),m.claimedBy(),m.claimToken(),m)).toList();
        };
    }
    static List<Lease> acquire(JdbcWorkflowPersistence p,Kind kind,Instant now,String owner,Instant until,int limit){return p.jdbcTransactions().inWriteTransaction(()->claim(p,kind,now,owner,until,limit));}
    static int release(JdbcWorkflowPersistence p,Kind kind,Instant now){return switch(kind){case TIMER->p.timers().releaseExpiredClaims(now);case INBOX->p.inbox().releaseExpiredClaims(now);case OUTBOX->p.outbox().releaseExpiredClaims(now);};}
    static void require(JdbcWorkflowPersistence p,Kind kind,Lease lease){switch(kind){case TIMER->p.timers().requireClaim(lease.id,lease.owner,lease.token);case INBOX->p.inbox().requireClaim(lease.id,lease.owner,lease.token);case OUTBOX->p.outbox().requireClaim(lease.id,lease.owner,lease.token);}}
    static void finish(JdbcWorkflowPersistence p,Kind kind,Lease lease,int operation,Instant time,boolean legacy){
        switch(kind){
            case TIMER->{if(legacy){if(operation==0)p.timers().markFired(lease.id,lease.owner,time);else p.timers().markFailed(lease.id,lease.owner,"failure",time);}
                else if(operation==0)p.timers().markFired(lease.id,lease.owner,lease.token,time);
                else if(operation==1)p.timers().markFailed(lease.id,lease.owner,lease.token,"failure",time);
                else p.timers().markDeadLetter(lease.id,lease.owner,lease.token,"failure",time);}
            case INBOX->{if(legacy){if(operation==0)p.inbox().markProcessed(lease.id,lease.owner,time);else if(operation==1)p.inbox().scheduleRetry(lease.id,lease.owner,time,"failure");else p.inbox().markDeadLetter(lease.id,lease.owner,"failure",time);}
                else if(operation==0)p.inbox().markProcessed(lease.id,lease.owner,lease.token,time);
                else if(operation==1)p.inbox().scheduleRetry(lease.id,lease.owner,lease.token,time,"failure");
                else p.inbox().markDeadLetter(lease.id,lease.owner,lease.token,"failure",time);}
            case OUTBOX->{if(legacy){if(operation==0)p.outbox().markPublished(lease.id,lease.owner,time);else if(operation==1)p.outbox().scheduleRetry(lease.id,lease.owner,time,"failure");else p.outbox().markDeadLetter(lease.id,lease.owner,"failure",time);}
                else if(operation==0)p.outbox().markPublished(lease.id,lease.owner,lease.token,time);
                else if(operation==1)p.outbox().scheduleRetry(lease.id,lease.owner,lease.token,time,"failure");
                else p.outbox().markDeadLetter(lease.id,lease.owner,lease.token,"failure",time);}
        }
    }
    static void history(JdbcWorkflowPersistence p,Kind kind,Lease lease){
        switch(kind){
            case TIMER->p.timers().appendAttempt(new WorkflowTimerAttempt(null,lease.id,1,WorkflowTimerStatus.FIRED,lease.owner,null,NOW));
            case INBOX->p.inbox().appendAttempt(new InboxAttempt(null,lease.id,1,InboxMessageStatus.PROCESSED,null,null,NOW));
            case OUTBOX->p.outbox().appendAttempt(new OutboxAttempt(null,lease.id,1,OutboxMessageStatus.PUBLISHED,null,null,NOW));
        }
        p.eventStatuses().append(new EventStatusAttempt(null,UUID.randomUUID(),null,1,"lease",null,"corr",EventStatusScope.INBOX,"test","",EventStatusValue.SUCCESSFUL,0,null,false,false,null,null,NOW));
    }
    static int histories(JdbcWorkflowPersistence p,Kind kind,UUID id){return switch(kind){case TIMER->p.timers().findAttempts(id).size();case INBOX->p.inbox().findAttempts(id).size();case OUTBOX->p.outbox().findAttempts(id).size();};}

    static void expiryAndFencing(JdbcWorkflowPersistence p,Kind kind,boolean postgres){
        UUID id=seed(p,kind);Instant until=NOW.plusSeconds(10);
        Lease old=acquire(p,kind,NOW,"same-worker",until,1).get(0);assertEquals(id,old.id);assertNotNull(old.token);
        assertTrue(acquire(p,kind,until.minusNanos(1),"other",until.plusSeconds(10),1).isEmpty());assertEquals(0,release(p,kind,until.minusNanos(1)));
        Lease current=acquire(p,kind,until,"same-worker",until.plusSeconds(10),1).get(0);assertEquals(id,current.id);assertNotEquals(old.token,current.token);
        if(postgres)for(int op=0;op<3;op++){int operation=op;assertThrows(UnsupportedOperationException.class,()->finish(p,kind,current,operation,until,true));}
        for(int op=0;op<3;op++){
            int operation=op;
            // Catching a failed final guard must still poison the outer transaction and remove earlier history.
            assertThrows(WorkflowPersistenceException.class,()->p.jdbcTransactions().inWriteTransaction(()->{
                history(p,kind,old);p.events().append(StorageValueContract.event(WorkflowInstanceId.random(),EventMessage.empty(),NOW));
                assertThrows(StaleWorkflowClaimException.class,()->finish(p,kind,old,operation,until,false));return null;
            }));assertEquals(0,histories(p,kind,id));
        }
        p.jdbcTransactions().inWriteTransaction(()->{require(p,kind,current);finish(p,kind,current,1,until.plusSeconds(20),false);return null;});
        assertTrue(acquire(p,kind,until.plusSeconds(19),"same-worker",until.plusSeconds(30),1).isEmpty());
        Lease retry=acquire(p,kind,until.plusSeconds(20),"same-worker",until.plusSeconds(30),1).get(0);assertNotEquals(current.token,retry.token);
        // Expiry by itself does not invalidate the generation; completion need not happen before claimUntil.
        p.jdbcTransactions().inWriteTransaction(()->{require(p,kind,retry);finish(p,kind,retry,0,until.plusSeconds(31),false);return null;});
        assertTrue(acquire(p,kind,until.plusSeconds(40),"worker",until.plusSeconds(50),1).isEmpty());
        seed(p,kind);Lease released=acquire(p,kind,NOW,"same-worker",until,1).get(0);
        assertEquals(1,release(p,kind,until));assertThrows(StaleWorkflowClaimException.class,()->finish(p,kind,released,0,until,false));
        Lease renewed=acquire(p,kind,until,"same-worker",until.plusSeconds(10),1).get(0);assertNotEquals(released.token,renewed.token);
        p.jdbcTransactions().inWriteTransaction(()->{finish(p,kind,renewed,2,until,false);return null;});
        assertTrue(acquire(p,kind,until.plusSeconds(20),"worker",until.plusSeconds(30),1).isEmpty());
    }
}
