package org.jworkflow.persistence;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

public interface WorkflowTransactionManager {
    void execute(WorkflowTransaction transaction);

    default <T> T inTransaction(WorkflowTransactionalWork<T> work) {
        java.util.Objects.requireNonNull(work, "work");
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        execute(() -> result.set(work.execute()));
        return result.get();
    }
}
