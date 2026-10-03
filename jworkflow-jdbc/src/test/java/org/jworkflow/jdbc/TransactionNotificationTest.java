package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionNotificationTest {
    @TempDir Path directory;
    SQLiteDataSource source() {
        SQLiteDataSource source=new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:"+directory.resolve(UUID.randomUUID()+".sqlite")); return source;
    }

    @Test void sqliteNestedCommandsNotifyOnlyAfterCommitAndCleanup() {
        assertDoesNotThrow(() -> TransactionNotificationContract.nestedCommands(WorkflowEngine.Type.SQLITE,source()));
    }
    @Test void sqliteEveryCommandPersistenceStageRollsBack() {
        assertDoesNotThrow(() -> TransactionNotificationContract.persistenceStagesRollback(WorkflowEngine.Type.SQLITE,source()));
    }
    @Test void sqliteInboxLaterCommandFailureDiscardsEarlierNotifications() {
        assertDoesNotThrow(() -> TransactionNotificationContract.inboxMultipleCommands(WorkflowEngine.Type.SQLITE,source()));
    }
    @Test void legacyCustomManagersRemainFunctionalAndCanOptIntoSynchronization() {
        WorkflowTransactionManager legacy=WorkflowTransaction::execute;
        List<Integer> order=new ArrayList<>();
        assertFalse(legacy.supportsAfterCommit());
        assertEquals("ok",legacy.inTransaction(()->"ok"));
        legacy.afterCommit(()->order.add(1));
        assertDoesNotThrow(()->legacy.afterCommit(()->{throw new IllegalStateException("observer");}));
        class Custom implements WorkflowTransactionManager {
            final List<Runnable> callbacks=new ArrayList<>();
            boolean active;
            public boolean supportsAfterCommit(){return true;}
            public void afterCommit(Runnable callback){if(active)callbacks.add(callback);else WorkflowTransactionManager.super.afterCommit(callback);}
            public void execute(WorkflowTransaction work){
                active=true;
                try{work.execute();}catch(Throwable failure){callbacks.clear();throw failure;}finally{active=false;}
                List<Runnable> completed=List.copyOf(callbacks);callbacks.clear();
                completed.forEach(this::afterCommit);
            }
        }
        Custom custom=new Custom();
        custom.execute(()->{custom.afterCommit(()->order.add(2));assertEquals(List.of(1),order);});
        assertEquals(List.of(1,2),order);
        assertThrows(IllegalStateException.class,()->custom.execute(()->{custom.afterCommit(()->order.add(3));throw new IllegalStateException();}));
        assertEquals(List.of(1,2),order);
    }
    @Test void sqlStatesAreClassifiedWithoutCopyingSecrets() {
        Map<String,JdbcTransactionException.Category> cases=Map.of("23505",JdbcTransactionException.Category.CONSTRAINT,
                "57014",JdbcTransactionException.Category.TIMEOUT,"55P03",JdbcTransactionException.Category.TIMEOUT,
                "40P01",JdbcTransactionException.Category.DEADLOCK,"40001",JdbcTransactionException.Category.SERIALIZATION,
                "08006",JdbcTransactionException.Category.CONNECTION,"57P01",JdbcTransactionException.Category.CONNECTION);
        cases.forEach((state,category)->{
            SQLException original=new SQLException("secret SQL credential",state);
            JdbcTransactionException failure=new JdbcTransactionException("commit",original);
            assertSame(original,failure.getCause()); assertEquals(category,failure.category());
            assertEquals(category,JdbcTransactionException.classify(new WorkflowPersistenceException("repository",original)));
            assertFalse(failure.getMessage().contains("secret"));
        });
    }
}
