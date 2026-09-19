package org.jworkflow.jdbc;

import org.jworkflow.inbox.*;
import org.jworkflow.outbox.*;
import org.jworkflow.persistence.*;
import org.jworkflow.model.*;
import org.jworkflow.events.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.lang.reflect.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.LeaseContract.*;

class LeaseCompatibilityTest {
    @TempDir Path directory;
    @TestFactory Stream<DynamicTest> sqliteFencedGenerationsAndLegacyOwnerMethods(){
        return Arrays.stream(Kind.values()).map(kind->DynamicTest.dynamicTest(kind.name(),()->{
            var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve(kind+".db"));
            var p=JdbcWorkflowPersistence.create(null,null,null,null,source,true,Map.of());expiryAndFencing(p,kind,false);
            assertEquals(0,TransactionNotificationContract.count(source,"event_status"));assertEquals(0,TransactionNotificationContract.count(source,"workflow_event"));
            seed(p,kind);Lease old=acquire(p,kind,NOW,"legacy",NOW.plusSeconds(1),1).get(0);
            Lease next=acquire(p,kind,NOW.plusSeconds(1),"legacy",NOW.plusSeconds(2),1).get(0);assertNotEquals(old.token(),next.token());
            // Legacy owner-only semantics intentionally cannot distinguish same-worker generations.
            finish(p,kind,old,0,NOW.plusSeconds(1),true);
        }));
    }
    @Test void existingConstructorsAndCustomRepositoryDescriptorsRemainUsable()throws Exception {
        UUID id=UUID.randomUUID();Instant until=NOW.plusSeconds(1);
        assertNull(new InboxClaim(id,"worker",NOW,until).claimToken());assertNull(new OutboxClaim(id,"worker",NOW,until).claimToken());
        assertNull(StorageValueContract.inbox(EventMessage.empty(),NOW).claimToken());assertNull(StorageValueContract.outbox(EventMessage.empty(),NOW).claimToken());
        assertNull(StorageValueContract.timer(WorkflowInstanceId.random(),NOW,null).claimToken());
        InboxClaim.class.getConstructor(UUID.class,String.class,Instant.class,Instant.class);
        OutboxClaim.class.getConstructor(UUID.class,String.class,Instant.class,Instant.class);
        WorkflowTimer.class.getConstructor(UUID.class,WorkflowInstanceId.class,String.class,Instant.class,String.class,EventName.class,WorkflowTimerStatus.class,int.class,Instant.class,String.class,Instant.class,Instant.class,Instant.class);
        InboxMessage.class.getConstructor(UUID.class,String.class,String.class,EventMessage.class,String.class,String.class,Instant.class,Instant.class,InboxMessageStatus.class,int.class,Instant.class,String.class,String.class,Instant.class);
        OutboxMessage.class.getConstructor(UUID.class,UUID.class,String.class,String.class,EventMessage.class,String.class,String.class,Instant.class,Instant.class,OutboxMessageStatus.class,int.class,Instant.class,String.class,String.class,Instant.class);
        for(Class<?> type:List.of(InboxRepository.class,OutboxRepository.class,WorkflowTimerRepository.class)){
            Object legacy=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                if(m.isDefault())return InvocationHandler.invokeDefault(p,m,a);throw new AssertionError("Fenced default delegated to legacy API");
            });
            var method=type.getMethod("requireClaim",UUID.class,String.class,String.class);
            var failure=assertThrows(InvocationTargetException.class,()->method.invoke(legacy,id,"worker","token"));assertInstanceOf(UnsupportedOperationException.class,failure.getCause());
        }
    }
}
