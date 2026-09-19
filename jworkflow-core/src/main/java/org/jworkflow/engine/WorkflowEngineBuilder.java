package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import org.jworkflow.observability.*;
import org.jworkflow.security.*;

import java.lang.reflect.InvocationTargetException;
import java.sql.Driver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import java.time.Clock;

public final class WorkflowEngineBuilder {
    private WorkflowEngine.Type type = WorkflowEngine.Type.IN_MEMORY;
    private String jdbcUrl;
    private String username;
    private String password;
    private Driver driver;
    private DataSource dataSource;
    private boolean initializeSchema;
    private final WorkflowDefinitionRegistry definitions = new WorkflowDefinitionRegistry();
    private EventPublisher eventPublisher = NoOpEventPublisher.INSTANCE;
    private WorkflowLifecycleObserver lifecycleObserver = NoOpWorkflowLifecycleObserver.INSTANCE;
    private WorkflowPersistence persistence;
    private final Map<String, StepHandler> stepHandlers = new LinkedHashMap<>();
    private final Map<String, Object> listenerInstances = new LinkedHashMap<>();
    private final Map<String, String> workflowStartEvents = new LinkedHashMap<>();
    private final BranchConditionEvaluator branchConditionEvaluator = new BranchConditionEvaluator();
    private final Map<String, String> settings = new LinkedHashMap<>();
    private DslCompilerOptions dslCompilerOptions = DslCompilerOptions.DEFAULT;
    private Clock clock = Clock.systemUTC();
    private EventCapturePolicy eventCapturePolicy = CaptureAllEventPolicy.INSTANCE;

    public WorkflowEngineBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    public WorkflowEngineBuilder timerPolling(boolean enabled) {
        return setting("recovery.timer-poll-enabled", Boolean.toString(enabled));
    }

    public WorkflowEngineBuilder timerPollIntervalMillis(long milliseconds) {
        if (milliseconds < 10 || milliseconds > 60_000) throw new IllegalArgumentException("timer poll interval must be between 10 and 60000 ms");
        return setting("recovery.timer-poll-interval-ms", Long.toString(milliseconds));
    }

    public WorkflowEngineBuilder recoveryLeaseMillis(long milliseconds) {
        if (milliseconds < 100 || milliseconds > 3_600_000) throw new IllegalArgumentException("recovery lease must be between 100 and 3600000 ms");
        return setting("recovery.lease-ms", Long.toString(milliseconds));
    }

    public WorkflowEngineBuilder timerBatchSize(int size) {
        return boundedSetting("recovery.timer-batch-size", size, 1, 1_000);
    }

    public WorkflowEngineBuilder timerRetryDelayMillis(long milliseconds) {
        return boundedSetting("recovery.timer-retry-delay-ms", milliseconds, 10, 3_600_000);
    }

    public WorkflowEngineBuilder startupValidationBatchSize(int size) {
        return boundedSetting("recovery.startup-validation-batch-size", size, 1, 10_000);
    }

    public WorkflowEngineBuilder eventRoutingMaximumCandidates(int size) {
        return boundedSetting("routing.maximum-candidates", size, 1, 10_000);
    }

    public WorkflowEngineBuilder lazyDefinitionValidation(boolean enabled) {
        return setting("recovery.lazy-definition-validation", Boolean.toString(enabled));
    }

    public WorkflowEngineBuilder inboxPollingMillis(long milliseconds) {
        return boundedSetting("inbox.poll-interval-ms", milliseconds, 10, 60_000);
    }

    public WorkflowEngineBuilder inboxClaimLeaseMillis(long milliseconds) {
        return boundedSetting("inbox.lease-ms", milliseconds, 100, 3_600_000);
    }

    public WorkflowEngineBuilder inboxBatchSize(int size) {
        return boundedSetting("inbox.batch-size", size, 1, 1_000);
    }

    public WorkflowEngineBuilder inboxRetry(int maximumAttempts, long initialBackoffMillis, long maximumBackoffMillis) {
        boundedSetting("inbox.max-attempts", maximumAttempts, 1, 100);
        boundedBackoff("inbox", initialBackoffMillis, maximumBackoffMillis);
        return this;
    }

    public WorkflowEngineBuilder outboxPollingMillis(long milliseconds) {
        return boundedSetting("outbox.poll-interval-ms", milliseconds, 10, 60_000);
    }

    public WorkflowEngineBuilder outboxClaimLeaseMillis(long milliseconds) {
        return boundedSetting("outbox.lease-ms", milliseconds, 100, 3_600_000);
    }

    public WorkflowEngineBuilder outboxBatchSize(int size) {
        return boundedSetting("outbox.batch-size", size, 1, 1_000);
    }

    public WorkflowEngineBuilder outboxRetry(int maximumAttempts, long initialBackoffMillis, long maximumBackoffMillis) {
        boundedSetting("outbox.max-attempts", maximumAttempts, 1, 100);
        boundedBackoff("outbox", initialBackoffMillis, maximumBackoffMillis);
        return this;
    }

    public WorkflowEngineBuilder type(WorkflowEngine.Type type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    public WorkflowEngineBuilder jdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    public WorkflowEngineBuilder username(String username) {
        this.username = username;
        return this;
    }

    public WorkflowEngineBuilder password(String password) {
        this.password = password;
        return this;
    }

    public WorkflowEngineBuilder driver(Driver driver) {
        this.driver = driver;
        return this;
    }

    public WorkflowEngineBuilder dataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public WorkflowEngineBuilder initialize() {
        this.initializeSchema = true;
        return this;
    }

    public WorkflowEngineBuilder initialize(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
        return this;
    }

    public WorkflowEngineBuilder definition(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        new DefinitionValidator().validate(definition).throwIfInvalid();
        definitions.register(definition);
        return this;
    }

    public WorkflowEngineBuilder definitions(WorkflowDefinitionSource source) {
        Objects.requireNonNull(source, "source");
        new WorkflowDefinitionActivationService(definitions, new GroovyWorkflowDslCompiler(dslCompilerOptions),
                new DefinitionValidator(), clock).activateOrThrow(source);
        return this;
    }

    public WorkflowEngineBuilder definition(org.jworkflow.definition.WorkflowDefinitionBuilder builder) {
        return definition(Objects.requireNonNull(builder, "builder").build());
    }

    public WorkflowEngineBuilder eventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        return this;
    }

    public WorkflowEngineBuilder lifecycleObserver(WorkflowLifecycleObserver observer) {
        this.lifecycleObserver = SafeWorkflowLifecycleObserver.isolate(
                Objects.requireNonNull(observer, "observer"));
        return this;
    }

    public WorkflowEngineBuilder eventCapturePolicy(EventCapturePolicy policy) {
        this.eventCapturePolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    public WorkflowEngineBuilder stepHandler(String action, StepHandler handler) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        stepHandlers.put(action, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    public WorkflowEngineBuilder dslCompilerOptions(DslCompilerOptions options) {
        this.dslCompilerOptions = Objects.requireNonNull(options, "options");
        return this;
    }

    public WorkflowEngineBuilder persistence(WorkflowPersistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        return this;
    }

    public WorkflowEngineBuilder startWorkflowOn(String eventName, String workflowKey) {
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName is required");
        }
        if (workflowKey == null || workflowKey.isBlank()) {
            throw new IllegalArgumentException("workflowKey is required");
        }
        workflowStartEvents.put(new EventName(eventName).value(), workflowKey);
        return this;
    }

    public WorkflowEngineBuilder listener(Object listener) {
        Objects.requireNonNull(listener, "listener");
        listenerInstances.put(listener.getClass().getName(), listener);
        return this;
    }

    public WorkflowEngineBuilder listener(String listenerId, Object listener) {
        if (listenerId == null || listenerId.isBlank()) {
            throw new IllegalArgumentException("listenerId is required");
        }
        listenerInstances.put(listenerId, Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public WorkflowEngineBuilder branchPredicate(
            String name,
            java.util.function.BiPredicate<Map<String, Object>, Map<String, Object>> predicate
    ) {
        branchConditionEvaluator.registerPredicate(name, predicate);
        return this;
    }

    public WorkflowEngineBuilder setting(String key, String value) {
        settings.put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    public WorkflowEngineBuilder sqliteBusyTimeoutMillis(int milliseconds) {
        if (milliseconds < 0 || milliseconds > 600_000) throw new IllegalArgumentException("SQLite busy timeout must be between 0 and 600000 ms");
        return setting("sqlite.busy-timeout-ms", Integer.toString(milliseconds));
    }

    /** WAL is opt-in because journal mode is a database-wide operational choice. */
    public WorkflowEngineBuilder sqliteWalEnabled(boolean enabled) {
        return setting("sqlite.wal-enabled", Boolean.toString(enabled));
    }

    public WorkflowEngineBuilder properties(WorkflowEngineProperties properties) {
        Objects.requireNonNull(properties, "properties");
        type = properties.type();
        jdbcUrl = properties.jdbcUrl();
        username = properties.username();
        password = properties.password();
        driver = properties.driver();
        dataSource = properties.dataSource();
        initializeSchema = properties.initializeSchema();
        settings.clear();
        settings.putAll(properties.settings());
        return this;
    }

    public WorkflowEngineBuilder properties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String configuredType = properties.getProperty("jworkflow.engine.type");
        if (configuredType != null && !configuredType.isBlank()) {
            type(WorkflowEngine.Type.valueOf(configuredType.trim().replace('-', '_').toUpperCase()));
        }

        jdbcUrl(properties.getProperty("jworkflow.jdbc.url", jdbcUrl));
        username(properties.getProperty("jworkflow.jdbc.username", username));
        password(properties.getProperty("jworkflow.jdbc.password", password));
        initializeSchema = Boolean.parseBoolean(properties.getProperty(
                "jworkflow.schema.initialize",
                Boolean.toString(initializeSchema)));
        if (properties.containsKey("jworkflow.sqlite.busy-timeout-ms")) {
            sqliteBusyTimeoutMillis(Integer.parseInt(properties.getProperty("jworkflow.sqlite.busy-timeout-ms")));
        }
        if (properties.containsKey("jworkflow.sqlite.wal-enabled")) {
            sqliteWalEnabled(Boolean.parseBoolean(properties.getProperty("jworkflow.sqlite.wal-enabled")));
        }

        for (String propertyName : properties.stringPropertyNames()) {
            if (propertyName.startsWith("jworkflow.setting.")) {
                setting(propertyName.substring("jworkflow.setting.".length()), properties.getProperty(propertyName));
            }
        }
        return this;
    }

    public WorkflowEngine build() throws ClassNotFoundException {
        validateConfiguration();
        DefinitionValidator validator = new DefinitionValidator();
        for (WorkflowDefinition definition : definitions.snapshot().values()) {
            validator.validate(definition, definitions).throwIfInvalid();
            if (persistence != null) {
                persistence.transactions().execute(() -> persistence.definitions().save(definition));
            }
        }
        WorkflowEngineProperties properties = new WorkflowEngineProperties(
                type,
                jdbcUrl,
                username,
                password,
                driver,
                dataSource,
                initializeSchema,
                settings);

        EventPublisher configuredPublisher = eventPublisher;
        EventPublisher lifecyclePublisher = new WorkflowEventLifecycleAdapter(lifecycleObserver);
        EventPublisher observedPublisher = event -> {
            configuredPublisher.publish(event);
            lifecyclePublisher.publish(event);
        };
        return switch (properties.type()) {
            case IN_MEMORY -> InMemoryWorkflowEngine.create(
                    definitions,
                    observedPublisher,
                    stepHandlers,
                    branchConditionEvaluator,
                    workflowStartEvents,
                    listenerInstances,
                    persistence,
                    eventCapturePolicy);
            case SQLITE, POSTGRESQL -> createJdbcEngine(properties, definitions, eventPublisher, stepHandlers,
                    branchConditionEvaluator, workflowStartEvents, listenerInstances, lifecycleObserver, clock,
                    eventCapturePolicy);
        };
    }

    private void validateConfiguration() {
        if (type != WorkflowEngine.Type.IN_MEMORY) {
            if (dataSource == null && (jdbcUrl == null || jdbcUrl.isBlank())) {
                throw new IllegalStateException(type + " durable mode requires jdbcUrl or dataSource");
            }
            if (persistence != null) {
                throw new IllegalStateException(type + " durable mode owns its JDBC persistence; do not also configure persistence(...)");
            }
        } else if (jdbcUrl != null || driver != null || dataSource != null || initializeSchema) {
            throw new IllegalStateException("JDBC configuration requires explicit SQLITE or POSTGRESQL engine type");
        }
    }

    private WorkflowEngineBuilder boundedBackoff(String prefix, long initial, long maximum) {
        if (initial > maximum) throw new IllegalArgumentException(prefix + " initial backoff must not exceed maximum backoff");
        boundedSetting(prefix + ".retry-initial-ms", initial, 10, 3_600_000);
        return boundedSetting(prefix + ".retry-maximum-ms", maximum, 10, 86_400_000);
    }

    private WorkflowEngineBuilder boundedSetting(String key, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return setting(key, Long.toString(value));
    }

    public WorkflowEngine buildAndSetInstance() throws ClassNotFoundException {
        WorkflowEngine engine = build();
        WorkflowEngine.setInstance(engine);
        return engine;
    }

    @SuppressWarnings("java:S107") // Reflection bridges the optional JDBC module without a core dependency.
    private static WorkflowEngine createJdbcEngine(
            WorkflowEngineProperties properties,
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers,
            BranchConditionEvaluator branchConditionEvaluator,
            Map<String, String> workflowStartEvents,
            Map<String, Object> listenerInstances,
            WorkflowLifecycleObserver lifecycleObserver,
            Clock clock,
            EventCapturePolicy eventCapturePolicy
    ) throws ClassNotFoundException {
        Class<?> engineClass = Class.forName("org.jworkflow.jdbc.JdbcWorkflowEngine");
        try {
            Object engine = engineClass.getMethod(
                            "create",
                            WorkflowEngine.Type.class,
                            String.class,
                            String.class,
                            String.class,
                            Driver.class,
                            DataSource.class,
                            Boolean.TYPE,
                            WorkflowDefinitionRegistry.class,
                            EventPublisher.class,
                            Map.class,
                            BranchConditionEvaluator.class,
                            Map.class,
                            Map.class,
                            Map.class,
                            WorkflowLifecycleObserver.class,
                            Clock.class,
                            EventCapturePolicy.class)
                    .invoke(
                            null,
                            properties.type(),
                            properties.jdbcUrl(),
                            properties.username(),
                            properties.password(),
                            properties.driver(),
                            properties.dataSource(),
                            properties.initializeSchema(),
                            definitions,
                            eventPublisher,
                            stepHandlers,
                            branchConditionEvaluator,
                            workflowStartEvents,
                            properties.settings(),
                            listenerInstances,
                            lifecycleObserver,
                            clock,
                            eventCapturePolicy);
            return (WorkflowEngine) engine;
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("jworkflow-jdbc is present but does not expose JdbcWorkflowEngine.create", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ClassNotFoundException classNotFoundException) {
                throw classNotFoundException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to create JDBC workflow engine", cause);
        }
    }
}
