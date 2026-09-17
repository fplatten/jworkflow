package org.jworkflow.engine;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class WorkflowEngines {
    private static final AtomicReference<WorkflowEngine> INSTANCE = new AtomicReference<>();

    private WorkflowEngines() {
    }

    public static WorkflowEngine instance() {
        WorkflowEngine engine = INSTANCE.get();
        if (engine == null) {
            throw new WorkflowInfrastructureException("WorkflowEngine has not been initialized", null);
        }
        return engine;
    }

    public static void setInstance(WorkflowEngine engine) {
        Objects.requireNonNull(engine, "engine");
        if (!INSTANCE.compareAndSet(null, engine)) {
            throw new WorkflowInfrastructureException("WorkflowEngine is already initialized", null);
        }
    }

    public static WorkflowEngine replaceInstance(WorkflowEngine engine) {
        Objects.requireNonNull(engine, "engine");
        return INSTANCE.getAndSet(engine);
    }

    public static void clearInstance() {
        INSTANCE.set(null);
    }
}
