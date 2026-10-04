package org.jworkflow.engine;

import org.jworkflow.internal.persistence.WorkflowPersistence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Persistence hooks belong to the in-memory runtime; a JDBC engine type must refuse them. */
class WorkflowEngineBuilderPersistenceTest {
    @Test
    void jdbcEngineRejectsCustomPersistence() {
        WorkflowPersistence persistence = (WorkflowPersistence) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WorkflowPersistence.class}, (proxy, method, args) -> null);
        var builder = WorkflowEngine.builder().type(WorkflowEngine.Type.POSTGRESQL).jdbcUrl("jdbc:unused")
                .persistence(persistence);
        assertThrows(IllegalStateException.class, builder::build);
    }
}
