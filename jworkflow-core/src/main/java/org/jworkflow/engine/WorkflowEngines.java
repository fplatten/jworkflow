package org.jworkflow.engine;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder for an explicitly installed default engine. Clearing or replacing the reference does not
 * transfer resource ownership; the host must close engines it creates.
 */
public final class WorkflowEngines {
    private static final AtomicReference<WorkflowEngine> INSTANCE = new AtomicReference<>();

    private WorkflowEngines() {
    }

    /**
     * Returns the installed process-wide engine, throwing if no engine is configured.
     * @return the installed process-wide engine, throwing if no engine is configured
     */
    public static WorkflowEngine instance() {
        WorkflowEngine engine = INSTANCE.get();
        if (engine == null) {
            throw new WorkflowInfrastructureException("WorkflowEngine has not been initialized", null);
        }
        return engine;
    }

    /**
     * Installs the default engine reference; does not close the previously referenced engine.
     * @param engine engine whose lifetime remains the caller's responsibility
     * @throws NullPointerException if engine is null
     */
    public static void setInstance(WorkflowEngine engine) {
        Objects.requireNonNull(engine, "engine");
        if (!INSTANCE.compareAndSet(null, engine)) {
            throw new WorkflowInfrastructureException("WorkflowEngine is already initialized", null);
        }
    }

    /**
     * Atomically replaces the default reference and returns the previous engine so its owner can manage cleanup.
     * @param engine engine whose lifetime remains the caller's responsibility
     * @return the configured engine; the caller must close it
     * @throws NullPointerException if engine is null
     */
    public static WorkflowEngine replaceInstance(WorkflowEngine engine) {
        Objects.requireNonNull(engine, "engine");
        return INSTANCE.getAndSet(engine);
    }

    /**
     * Removes the default engine reference without closing the engine.
     */
    public static void clearInstance() {
        INSTANCE.set(null);
    }
}
