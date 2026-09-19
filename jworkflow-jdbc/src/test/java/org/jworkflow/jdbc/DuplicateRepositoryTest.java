package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.inbox.InboxMessage;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.jworkflow.jdbc.DuplicateRepositoryContract.*;

class DuplicateRepositoryTest {
    @TempDir Path directory;
    @Test void sqliteBinaryJsonAndEnvelopeDuplicatesValidateByStoredContent(){envelopeAndTypeConflicts(ports());}

    @Test void sqliteMatchingAndConflictingDuplicatesDirectAndNested() {
        for(Kind kind:Kind.values())for(boolean nested:List.of(false,true)) {
            var ports=ports();Object first=sample(kind,"first");
            save(ports,first);
            java.util.function.Supplier<Object> duplicate=()->save(ports,duplicate(first,false)).value();
            assertEquals(first,nested?ports.jdbcTransactions().inWriteTransaction(duplicate::get):duplicate.get());
            java.util.function.Supplier<Object> conflict=()->save(ports,duplicate(first,true));
            Class<? extends RuntimeException> type=kind==Kind.INBOX?WorkflowInfrastructureException.class:PersistenceConstraintException.class;
            assertThrows(type,()->{if(nested)ports.jdbcTransactions().inWriteTransaction(conflict::get);else conflict.get();});
            assertEquals(first,find(ports,first));
            Object next=sample(kind,"next");assertEquals(next,save(ports,next).value());
        }
    }

    @Test void inboxRetainsFirstArrivalAndCommandResultsValidateBothTypeAndHash() {
        var ports=ports();InboxMessage first=(InboxMessage)sample(Kind.INBOX,"first");save(ports,first);
        InboxMessage other=(InboxMessage)sample(Kind.INBOX,"other");
        InboxMessage redelivery=new InboxMessage(other.messageId(),first.externalEventId(),first.sourceSystem(),
                org.jworkflow.events.EventMessage.empty(),"different-corr","different-cause",other.receivedAt(),null,null,0,null,null,null,null);
        assertEquals(first,save(ports,redelivery).value(),"inbox contract is identity-only first arrival");
        var result=(CommandResultRecord)sample(Kind.COMMAND,"command");save(ports,result);
        var typeConflict=new CommandResultRecord(result.idempotencyKey(),"cancel",result.requestHash(),null,Map.of(),NOW);
        assertThrows(PersistenceConstraintException.class,()->save(ports,typeConflict));
        assertEquals(result,find(ports,result));
        var definition=(WorkflowDefinition)sample(Kind.DEFINITION,"definition");save(ports,definition);
        var revision=new WorkflowDefinition(definition.name(),definition.version(),definition.startNode(),definition.nodes(),Map.of("revision","two"));
        assertNotEquals(definition.revision(),revision.revision());save(ports,revision);
        assertEquals(definition,find(ports,definition));assertEquals(revision,find(ports,revision));
    }

    @Test void sqliteInboxNoLongerIgnoresUnrelatedNotNullOrCheckFailures()throws Exception {
        for(String constraint:List.of("not null","check (guard is not null)")) {
            Path path=directory.resolve(UUID.randomUUID()+".sqlite");String url="jdbc:sqlite:"+path;
            var ports=JdbcWorkflowPersistence.create(url,null,null,null,null,true,Map.of());
            try(Connection connection=DriverManager.getConnection(url);Statement statement=connection.createStatement()) {
                statement.execute("alter table workflow_inbox add column guard integer "+constraint);
            }
            assertThrows(WorkflowInfrastructureException.class,()->ports.jdbcTransactions().inWriteTransaction(()->save(ports,sample(Kind.INBOX,"invalid"))));
            try(Connection connection=DriverManager.getConnection(url);Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("select count(*) from workflow_inbox")){
                assertTrue(rows.next());assertEquals(0,rows.getInt(1));
            }
        }
    }

    private JdbcWorkflowPersistence ports(){return JdbcWorkflowPersistence.create("jdbc:sqlite:"+directory.resolve(UUID.randomUUID()+".sqlite"),null,null,null,null,true,Map.of());}
}
